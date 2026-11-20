package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadsetSource
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import java.util.Collections
import java.util.IdentityHashMap

/** The parts of `AudioManager` a CarPlay call or Siri changes. */
internal interface AudioModeSystem {
    /** `AudioManager.getMode()` / `setMode()`. */
    var mode: Int

    /** Routes communication to the built-in loudspeaker (`setCommunicationDevice`); false when refused. */
    fun loudspeaker(): Boolean

    /** `AudioManager.getAvailableCommunicationDevices()`. */
    fun communicationDevices(): List<CommunicationDevice>

    /** `AudioManager.setCommunicationDevice()` with [device]; false when refused or gone. */
    fun setCommunicationDevice(device: CommunicationDevice): Boolean

    /** `clearCommunicationDevice()`. */
    fun clearCommunicationDevice()

    /** `AudioManager.getCommunicationDevice()`: the device communication is routed to now; null when none. */
    fun activeCommunicationDevice(): CommunicationDevice?

    /** Calls [changed] with the new active device each time it changes (`addOnCommunicationDeviceChangedListener`). */
    fun watchCommunicationDevice(changed: (CommunicationDevice?) -> Unit)
}

/**
 * The audio route of CarPlay's calls and Siri (docs/PLAN.md "C5 audio", "Routes").
 *
 * **On the helmet.** When a call's or Siri's sound or microphone starts ([voiceStarted]) and the rider's Bluetooth
 * headset is connected ([chooseHeadset] with [headsets]), it becomes the communication device
 * (`AudioManager.setCommunicationDevice`, the API 31 replacement for `startBluetoothSco`, which opens the hands-free
 * link outside a phone call too; developer.android.com "Audio routing API updates in Android 14 for VoIP apps",
 * "Managing BLE Audio for Communication Applications with AudioManager"). The tracks and the recorder also prefer it
 * (`setPreferredDevice`), because Siri's `USAGE_ASSISTANT` track and `VOICE_RECOGNITION` recorder do not follow the
 * communication route by themselves. It is cleared (`clearCommunicationDevice`) when the last of them ends.
 *
 * **Without a headset** (none connected, or the rider chose the phone for calls) this is DiPlay's behaviour
 * (AndroidMediaSink.kt:581-678, CallSpeaker.kt): a call that plays on the earpiece, which nobody hears on a bike,
 * moves to the loudspeaker once per call (#701, #782). A headset that disconnects mid-call drops Android's route
 * (the selection lasts "until the device is disconnected", setCommunicationDevice reference), the call falls to the
 * earpiece, and the same fallback applies.
 *
 * **A request, not a route.** `setCommunicationDevice` returning true only means the request was taken; the route is
 * applied when the communication device changes to the headset ("Managing BLE Audio for Communication Applications":
 * wait for the communication device to become the selected one, with a timeout, e.g. 30 s; listen for the change). The
 * route counts as confirmed once [AudioModeSystem.activeCommunicationDevice] is the headset. A confirmed headset that
 * Android then leaves (a dropped or refused SCO link while the intercom stays listed), or one still not applied
 * [CONFIRM_MILLIS] after the request when a call's sound lands on the earpiece, is a failed route: it is forgotten and
 * the call gets the loudspeaker fallback. Before that an earpiece report is a passing one (SCO still coming up).
 *
 * **The call's mode.** The call's microphone puts the phone in `MODE_IN_COMMUNICATION` before it records ([enter]:
 * the platform's echo canceller and the voice path need it) and leaves with `MODE_NORMAL` ([leave]), which withdraws
 * this process's request rather than restoring an old mode (DiPlay #987). Siri keeps the normal mode, as in DiPlay.
 *
 * No persisted undo flag (docs/LESSONS.md 57) is needed: AudioService drops a process's mode request and its
 * communication device when the process dies (`AudioService.setMode` death handler, `AudioDeviceBroker`
 * communication route clients), so nothing outlives a crash.
 */
internal class CallAudio(
    private val system: AudioModeSystem,
    private val headsets: CarPlayHeadsetSource,
    private val log: Logger,
    /** Elapsed time, for [CONFIRM_MILLIS]. */
    private val time: TimeProvider,
) {
    /**
     * The call microphones that asked for the mode and have not left yet. Usually one; two while a replaced telephony
     * stream's capture is still releasing as the new one starts: the old one leaving must not end the new call's
     * mode.
     */
    private val callers = HashSet<Any>()
    private var loudspeaker = false
    private var loudspeakerTried = false

    /** The tracks and recorders of calls and Siri that are running now. */
    private val voices: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    /** The headset that is the communication device now, by our request. */
    private var headset: CommunicationDevice? = null

    /** Android applied [headset] (it became the active communication device); when it was requested. */
    private var confirmed = false
    private var requestedAt = 0L

    init {
        system.watchCommunicationDevice(::communicationDeviceChanged)
    }

    /**
     * A track or recorder of [channel] starts; the headset the calls and Siri use now, which it should prefer, or
     * null for Android's own route (music and directions always get null: A2DP carries them by itself).
     */
    @Synchronized
    fun voiceStarted(
        user: Any,
        channel: AudioChannel,
    ): CommunicationDevice? {
        if (!channel.voiceUsesHeadset) return null
        voices += user
        if (headset == null) routeToHeadset(channel)
        return headset
    }

    /** A track or recorder from [voiceStarted] ended; the last one gives the route back to Android. */
    @Synchronized
    fun voiceEnded(user: Any) {
        if (!voices.remove(user) || voices.isNotEmpty()) return
        headset?.let {
            system.clearCommunicationDevice()
            log.info("carplay.audio.headset_route", "device" to it.name, "cleared" to true)
        }
        headset = null
        confirmed = false
    }

    /** [call]'s microphone is about to record; the first one puts the phone in communication mode. */
    @Synchronized
    fun enter(call: Any) {
        if (!callers.add(call) || callers.size > 1) return
        val before = system.mode
        system.mode = AudioManager.MODE_IN_COMMUNICATION
        loudspeakerTried = false
        log.info("carplay.audio.mode", "from" to before, "to" to system.mode, "reason" to "call")
    }

    /** [call]'s microphone stopped; the last one to leave gives the mode back. */
    @Synchronized
    fun leave(call: Any) {
        if (!callers.remove(call) || callers.isNotEmpty()) return
        if (loudspeaker) system.clearCommunicationDevice()
        loudspeaker = false
        system.mode = AudioManager.MODE_NORMAL
        log.info("carplay.audio.mode", "to" to system.mode, "reason" to "call ended")
    }

    /**
     * A call's track was routed to the earpiece: while the headset's route is applied, or still coming up, that is a
     * passing report and is ignored; otherwise (no headset, a lost one, a route that failed) the call moves to the
     * loudspeaker, once per call.
     */
    @Synchronized
    fun routedToEarpiece() {
        if (callers.isEmpty() || loudspeaker || headsetHolds()) return
        fallBackToLoudspeaker()
    }

    /** Android's communication device changed to [device] (the platform's listener). */
    @Synchronized
    fun communicationDeviceChanged(device: CommunicationDevice?) {
        val routed = headset ?: return
        if (device?.id == routed.id) {
            if (!confirmed) log.info("carplay.audio.headset_confirmed", "device" to routed.name)
            confirmed = true
        } else if (confirmed || time.elapsedMillis() - requestedAt >= CONFIRM_MILLIS) {
            headsetFailed(routed, "route left")
        }
    }

    /**
     * Whether the headset we routed to still carries the call, or may still be coming up. A disconnected one is
     * forgotten (Android dropped its route); one whose route Android did not apply, or left, is a failed route.
     */
    private fun headsetHolds(): Boolean {
        val routed = headset ?: return false
        val active = system.activeCommunicationDevice()?.id == routed.id
        if (active) confirmed = true
        val waiting = !confirmed && time.elapsedMillis() - requestedAt < CONFIRM_MILLIS
        val listed = system.communicationDevices().any { it.id == routed.id }
        if (!listed) {
            headset = null
            confirmed = false
            log.info("carplay.audio.headset_lost", "device" to routed.name, "reason" to "disconnected")
        } else if (!active && !waiting) {
            headsetFailed(routed, "route not applied")
        }
        return listed && (active || waiting)
    }

    /** The headset's route failed while it is still listed: forget it and withdraw the request. */
    private fun headsetFailed(
        routed: CommunicationDevice,
        reason: String,
    ) {
        headset = null
        confirmed = false
        log.info("carplay.audio.headset_lost", "device" to routed.name, "reason" to reason)
        system.clearCommunicationDevice()
        if (callers.isNotEmpty() && !loudspeaker) fallBackToLoudspeaker()
    }

    /** DiPlay's fallback: the call moves to the loudspeaker, once per call. */
    private fun fallBackToLoudspeaker() {
        if (loudspeakerTried) return
        loudspeakerTried = true
        loudspeaker = system.loudspeaker()
        log.info("carplay.audio.call_route", "earpiece" to true, "loudspeaker" to loudspeaker)
    }

    private fun routeToHeadset(channel: AudioChannel) {
        val chosen = chooseHeadset(system.communicationDevices(), headsets.preferred())
        if (chosen == null) {
            log.info("carplay.audio.headset_route", "device" to null, "channel" to channel)
            return
        }
        val routed = system.setCommunicationDevice(chosen)
        if (routed) {
            // The headset replaces a loudspeaker this call chose before it connected.
            loudspeaker = false
            headset = chosen
            requestedAt = time.elapsedMillis()
            confirmed = system.activeCommunicationDevice()?.id == chosen.id
        }
        log.info(
            "carplay.audio.headset_route",
            "device" to chosen.name,
            "type" to chosen.type,
            "channel" to channel,
            "routed" to routed,
        )
    }

    companion object {
        /** How long a requested headset route may take to apply (the AudioManager guide's example timeout). */
        const val CONFIRM_MILLIS = 30_000L
    }
}

package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadsetSource
import com.abid.handlebarlink.core.common.log.Logger
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
) {
    private var owner: Any? = null
    private var loudspeaker = false
    private var loudspeakerTried = false

    /** The tracks and recorders of calls and Siri that are running now. */
    private val voices: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    /** The headset that is the communication device now, by our request. */
    private var headset: CommunicationDevice? = null

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
    }

    /** [call]'s microphone is about to record. */
    @Synchronized
    fun enter(call: Any) {
        if (owner != null) return
        val before = system.mode
        system.mode = AudioManager.MODE_IN_COMMUNICATION
        owner = call
        loudspeakerTried = false
        log.info("carplay.audio.mode", "from" to before, "to" to system.mode, "reason" to "call")
    }

    /** [call]'s microphone stopped; only its owner leaves the mode. */
    @Synchronized
    fun leave(call: Any) {
        if (owner !== call) return
        owner = null
        if (loudspeaker) system.clearCommunicationDevice()
        loudspeaker = false
        system.mode = AudioManager.MODE_NORMAL
        log.info("carplay.audio.mode", "to" to system.mode, "reason" to "call ended")
    }

    /**
     * A call's track was routed to the earpiece: with the headset still connected that is a passing report and is
     * ignored; otherwise the call moves to the loudspeaker, once per call.
     */
    @Synchronized
    fun routedToEarpiece() {
        if (owner == null || loudspeaker || headsetStillThere()) return
        if (loudspeakerTried) return
        loudspeakerTried = true
        loudspeaker = system.loudspeaker()
        log.info("carplay.audio.call_route", "earpiece" to true, "loudspeaker" to loudspeaker)
    }

    /** Whether the headset we routed to is still connected; a lost one is forgotten (Android dropped its route). */
    private fun headsetStillThere(): Boolean {
        val routed = headset ?: return false
        val there = system.communicationDevices().any { it.id == routed.id }
        if (!there) {
            headset = null
            log.info("carplay.audio.headset_lost", "device" to routed.name)
        }
        return there
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
        }
        log.info(
            "carplay.audio.headset_route",
            "device" to chosen.name,
            "type" to chosen.type,
            "channel" to channel,
            "routed" to routed,
        )
    }
}

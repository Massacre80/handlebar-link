package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import com.abid.handlebarlink.core.common.log.Logger

/** The parts of `AudioManager` a CarPlay call changes. */
internal interface AudioModeSystem {
    /** `AudioManager.getMode()` / `setMode()`. */
    var mode: Int

    /** Routes communication to the built-in loudspeaker (`setCommunicationDevice`); false when refused. */
    fun loudspeaker(): Boolean

    /** `clearCommunicationDevice()`. */
    fun clearCommunicationDevice()
}

/**
 * A CarPlay call's audio mode (DiPlay AndroidMediaSink.kt:581-678, CallSpeaker.kt): the call's microphone puts the
 * phone in `MODE_IN_COMMUNICATION` before it records (the platform's echo canceller and the voice path need it) and
 * leaves with `MODE_NORMAL`, which withdraws this process's request rather than restoring an old mode (#987). A phone
 * used as the head unit plays that mode on its earpiece, which nobody hears on a bike: once a call's track is routed
 * there, the call moves to the loudspeaker, once per call (#701, #782).
 *
 * No persisted undo flag (docs/LESSONS.md 57) is needed: AudioService drops a process's mode request and its
 * communication device when the process dies (`AudioService.setMode` death handler,
 * `AudioDeviceBroker` communication route clients), so nothing outlives a crash.
 */
internal class CallAudio(
    private val system: AudioModeSystem,
    private val log: Logger,
) {
    private var owner: Any? = null
    private var loudspeaker = false
    private var loudspeakerTried = false

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

    /** A call's track was routed to the earpiece. */
    @Synchronized
    fun routedToEarpiece() {
        if (owner == null || loudspeaker || loudspeakerTried) return
        loudspeakerTried = true
        loudspeaker = system.loudspeaker()
        log.info("carplay.audio.call_route", "earpiece" to true, "loudspeaker" to loudspeaker)
    }
}

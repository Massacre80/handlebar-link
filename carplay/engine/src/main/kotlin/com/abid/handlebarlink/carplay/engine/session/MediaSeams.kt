package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.spi.AudioPort
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneOffer
import com.abid.handlebarlink.carplay.engine.spi.MicrophonePort
import com.abid.handlebarlink.carplay.engine.spi.NetworkPort
import com.abid.handlebarlink.carplay.engine.spi.ScreenPort
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider

// Where the media code plugs into the AirPlay session. The session (this package) negotiates the streams, binds their
// ports, accepts only the control connection's peer and derives each stream's key; what flows on a stream is the
// business of these seams. [MediaFactory.receivers] (MediaReceivers.kt) builds the real ones: the timing exchange, the
// screen reader and the audio streams; decrypted frames go on to the platform's [ScreenPort], decrypted audio to its
// [AudioPort], and the microphone of [MicrophonePort] back to the iPhone.

/**
 * Answers the iPhone's NTP-style timing exchange on the session SETUP's `timingPort` (DiPlay NtpClock.kt, spec D
 * §2.8): answers its type-210 requests and, when [peerPort] > 0, asks [peer]:[peerPort] for its time every second.
 */
internal fun interface TimingResponder {
    /**
     * Serves [socket] until cancelled. [peer] is the control connection's peer (DiPlay sends only there,
     * AirPlaySession.kt:855-858); [peerPort] is the iPhone's `timingPort` from the session SETUP, 0 when it sent none.
     * Returning or throwing ends only the timing exchange, never the session (DiPlay keeps the session then).
     */
    suspend fun run(
        socket: UdpSocket,
        peer: String,
        peerPort: Int,
    )
}

/** Commands to the iPhone on the encrypted event channel (DiPlay AirPlaySession.kt:305-348). */
internal fun interface EventCommands {
    /** Sends [command] as `POST /command`; false while the event channel is not connected or after it failed. */
    suspend fun send(command: Plist.Dict): Boolean
}

/** One accepted screen stream, as its SETUP entry asked for it (DiPlay AirPlaySession.kt:743-755). */
internal class ScreenStreamSetup(
    /** 110 main screen, 111 alternate (cluster) screen. */
    val type: Int,
    /** The stream's `DataStream-Output-Encryption-Key` (CarPlayMediaEngine.kt:620-622, 665-677). Never log it. */
    val key: ByteArray,
    /** The SETUP entry as sent, for any other key the reader needs. */
    val request: Plist.Dict,
)

/**
 * Reads one screen stream: 128-byte headers, clear codec configs and ChaCha20-Poly1305 sealed frames (DiPlay
 * ScreenStream.kt). The session has already accepted the connection from the iPhone and closes it afterwards.
 */
internal fun interface ScreenStreamSink {
    /**
     * The stream SETUP accepted [stream], before its connection exists: the decoder is prepared now, so its start
     * does not fall on the first pictures (docs/LESSONS.md 77).
     */
    fun prepare(stream: ScreenStreamSetup) {}

    /**
     * Reads [connection] until it ends (return) or fails (throw), or until cancelled by a TEARDOWN. As in DiPlay, the
     * stream ending without a TEARDOWN ends the whole session (CarPlayMediaEngine.kt:160-169). [events] asks the
     * iPhone for a key frame (`forceKeyFrame`, CarPlayMediaEngine.kt:146-157).
     */
    suspend fun run(
        stream: ScreenStreamSetup,
        connection: TcpConnection,
        events: EventCommands,
    )
}

/** One accepted audio stream, as its SETUP entry asked for it (DiPlay CarPlayMediaEngine.kt:177-204). */
internal class AudioStreamSetup(
    /** 100 main, 101 alternate, 102 main high audio. */
    val type: Int,
    /** `audioType`, lower-cased, "default" when absent (CarPlayMediaEngine.kt:178). */
    val audioType: String,
    /** The negotiated `audioFormat` bits, 0 when absent (CarPlayMediaEngine.kt:190-191). */
    val audioFormat: Long,
    /** `streamConnectionID` exactly as the iPhone sent it (echoed in the reply). */
    val streamConnectionId: Plist.Integer,
    /** The stream's `DataStream-Output-Encryption-Key`. Never log it. */
    val key: ByteArray,
    /** The SETUP entry as sent (audioLatencyMs, framesPerPacket, input, vocoderInfo, the microphone's dataPort). */
    val request: Plist.Dict,
    /** The microphone's `DataStream-Input-Encryption-Key` (CarPlayMediaEngine.kt:635, spec C §8). Never log it. */
    val inputKey: ByteArray = ByteArray(0),
)

/**
 * Receives audio streams: RTP on the data socket (DiPlay AudioStream.kt). The session itself reads and drops what
 * arrives on the RTCP control socket, as DiPlay does (AudioStream.kt:180-190).
 */
internal interface AudioStreamSink {
    /**
     * Receives [stream] on [data] until cancelled by a TEARDOWN, a replacing SETUP or the session's end. Returning or
     * throwing ends only this stream (DiPlay keeps the session). The session closes the socket afterwards.
     */
    suspend fun run(
        stream: AudioStreamSetup,
        data: UdpSocket,
    )

    /**
     * This stream's `/feedback` entry (`type`, `sampleRate`, then `streamConnectionID`, `timestamp`, `timestampRawNs`
     * and `sampleTime` once samples arrived, CarPlayMediaEngine.kt:489-523), or null to leave it out. With no entry
     * at all `/feedback` gets an empty 200, as DiPlay answers when no audio stream is active.
     */
    fun feedback(stream: AudioStreamSetup): Plist.Dict? = null

    /** `duckAudio` ([ducked] true) or `unduckAudio` from the iPhone (AirPlaySession.kt:810-811). */
    fun duck(ducked: Boolean) {}

    /** The session ended (CarPlayMediaEngine.kt:551-568). */
    fun close() {}
}

/**
 * What a session's media seams may use: the injected clock, the log, the platform's ChaCha20-Poly1305 name, the
 * attempt's configuration (the canvas `/info` reported), the sockets, the control connection's [peer] (the microphone's
 * destination, CarPlayMediaEngine.kt:634) and the [microphone] `/info` offered.
 */
internal class MediaContext(
    private val platform: SessionPlatform,
    val log: Logger,
    val peer: String,
) {
    val time: TimeProvider get() = platform.time
    val cipherName: String get() = platform.cipherName
    val config: CarPlayConfig get() = platform.config
    val network: NetworkPort get() = platform.network
    val microphone: MicrophoneOffer get() = platform.microphone
}

/**
 * Builds the media seams of one AirPlay session (one verified control connection), so state that belongs to a session
 * (the timing clock that `/feedback` timestamps read) is shared by its seams and never by two sessions.
 */
internal fun interface MediaFactory {
    fun session(context: MediaContext): MediaSeams

    companion object {
        /**
         * The real seams (MediaReceivers.kt, AudioReceiver.kt): one
         * [com.abid.handlebarlink.carplay.protocol.timing.TimingClock] per session, the main screen stream's frames
         * handed to [screen] (the platform's decoder, docs/PLAN.md C4), audio decrypted and played on [audio], and the
         * recording streams' [microphone] sent to the iPhone (C5).
         */
        fun receivers(
            screen: ScreenPort,
            audio: AudioPort = AudioPort.NONE,
            microphone: MicrophonePort = MicrophonePort.NONE,
        ): MediaFactory {
            val outputs = AudioOutputs(audio, microphone)
            return MediaFactory { context -> SessionMedia(context, screen, outputs).seams }
        }
    }
}

/** One session's media seams. */
internal class MediaSeams(
    val timing: TimingResponder,
    val screen: ScreenStreamSink,
    val audio: AudioStreamSink,
)

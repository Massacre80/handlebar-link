package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider

// Where the media code plugs into the AirPlay session. The session (this package) negotiates the streams, binds their
// ports, accepts only the control connection's peer and derives each stream's key; what flows on a stream is the
// business of these seams. [MediaSeams.placeholders] only drains and logs, so the iPhone keeps the session while the
// real readers (timing and stream decryption, the video decoder) are built elsewhere and wired in at merge.

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
}

/** What a session's media seams may use: the injected clock, the log and the platform's ChaCha20-Poly1305 name. */
internal class MediaContext(
    val time: TimeProvider,
    val log: Logger,
    val cipherName: String,
)

/**
 * Builds the media seams of one AirPlay session (one verified control connection), so state that belongs to a session
 * (the timing clock that `/feedback` timestamps read) is shared by its seams and never by two sessions.
 */
internal fun interface MediaFactory {
    fun session(context: MediaContext): MediaSeams

    companion object {
        /** Until the media readers are wired in. */
        val PLACEHOLDERS = MediaFactory { MediaSeams.placeholders(it.log) }
    }
}

/** One session's media seams. */
internal class MediaSeams(
    val timing: TimingResponder,
    val screen: ScreenStreamSink,
    val audio: AudioStreamSink,
) {
    companion object {
        /**
         * Seams that only receive and log (the first bytes of each stream at info): they keep every socket read so the
         * iPhone's sends never block, but answer nothing. The real timing responder, stream readers and decoder replace
         * them here.
         */
        fun placeholders(log: Logger): MediaSeams =
            MediaSeams(
                timing = { socket, _, _ ->
                    var seen = 0L
                    while (socket.receive() != null) {
                        if (seen++ == 0L) log.info("carplay.timing.first_packet", "port" to socket.port)
                    }
                },
                screen = { stream, connection, _ -> drain(stream.type, connection, log) },
                audio = DrainingAudio(log),
            )

        private suspend fun drain(
            type: Int,
            connection: TcpConnection,
            log: Logger,
        ) {
            val buffer = ByteArray(DRAIN_CHUNK)
            var total = 0L
            while (true) {
                val count = connection.read(buffer)
                if (count < 0) break
                if (total == 0L) log.info("carplay.screen.receiving", "type" to type, "bytes" to count)
                total += count
            }
            log.info("carplay.screen.ended", "type" to type, "bytes" to total)
        }

        private const val DRAIN_CHUNK = 64 * 1024
    }

    private class DrainingAudio(
        private val log: Logger,
    ) : AudioStreamSink {
        override suspend fun run(
            stream: AudioStreamSetup,
            data: UdpSocket,
        ) {
            var seen = 0L
            while (data.receive() != null) {
                if (seen++ == 0L) {
                    log.info("carplay.audio.receiving", "type" to stream.type, "audioType" to stream.audioType)
                }
            }
        }
    }
}

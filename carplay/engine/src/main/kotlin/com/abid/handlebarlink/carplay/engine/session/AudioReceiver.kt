package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.AudioPlayer
import com.abid.handlebarlink.carplay.engine.spi.AudioPort
import com.abid.handlebarlink.carplay.engine.spi.AudioStreamInfo
import com.abid.handlebarlink.carplay.engine.spi.MicrophonePort
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.carplay.protocol.audio.AudioFormat
import com.abid.handlebarlink.carplay.protocol.audio.AudioPacket
import com.abid.handlebarlink.carplay.protocol.audio.RealtimeAudioDecryptor
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.carplay.protocol.timing.TimingClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * What every session's audio shares: the platform's sound and microphone (they outlive sessions), and the session
 * whose last `duckAudio` started a prompt (DiPlay `duckingSession`, CarPlayMediaEngine.kt:106-107). Used only on the
 * engine's dispatcher.
 */
internal class AudioOutputs(
    val port: AudioPort,
    val microphone: MicrophonePort,
) {
    var ducking: AudioReceiver? = null
}

/**
 * One session's audio streams (DiPlay CarPlayMediaEngine.kt:177-277, 489-553). Each datagram is decrypted with
 * [RealtimeAudioDecryptor] (AudioStream.kt:91-178); a datagram that is too short or does not authenticate is dropped
 * and the stream goes on, as DiPlay. The first good packet opens the stream's [AudioPlayer] on the platform's
 * [AudioPort] (MediaSink.onAudioStarted, :224-228), and every good packet goes to it in arrival order; the player
 * closes when the stream ends (TEARDOWN, a replacing SETUP, the session's end). A player that fails is closed and the
 * stream goes on silently: a dead audio device never ends the session.
 *
 * A recording stream ([MicrophoneStream]) also sends the microphone ([MicrophoneUplink]) from the first downlink
 * packet, or [MicrophoneStream.SILENT_START_MILLIS] after its SETUP for streams other than calls
 * (MicrophoneStart.kt:8-51), until the stream ends.
 *
 * [feedback] is DiPlay's per-stream `/feedback` entry (CarPlayMediaEngine.kt:489-523): `type` and `sampleRate` (the
 * stream's RTP clock rate) from the SETUP on; once the first packet was decrypted also `streamConnectionID`,
 * `timestamp` (the session's timing clock), `timestampRawNs` (the monotonic clock) and `sampleTime`: the first
 * packet's RTP timestamp plus the time since it arrived, less the SETUP's `audioLatencyMs`, on the RTP clock, as u32.
 */
internal class AudioReceiver(
    private val context: MediaContext,
    private val clock: TimingClock,
    private val outputs: AudioOutputs,
) : AudioStreamSink {
    /** When a stream's first packet was decrypted (CarPlayMediaEngine.kt:224-227). */
    private class Start(
        val firstSample: Long,
        val originNanos: Long,
    )

    /** The running streams' starts, by identity: a replacing SETUP is a new [AudioStreamSetup]. */
    private val starts = HashMap<AudioStreamSetup, Start>()

    override suspend fun run(
        stream: AudioStreamSetup,
        data: UdpSocket,
    ) {
        val running = StreamRun(stream)
        coroutineScope {
            val microphone = MicrophoneStream.of(stream, context.microphone)
            val uplink = microphone?.let { launch { microphone(stream, it, running.firstPacket) } }
            try {
                while (true) {
                    val datagram = data.receive() ?: break
                    running.receive(datagram.bytes)
                }
            } finally {
                uplink?.cancel()
                running.close()
            }
        }
    }

    /** The stream's microphone, from the first downlink packet or the silent start (MicrophoneStart.kt:15-51). */
    private suspend fun microphone(
        stream: AudioStreamSetup,
        microphone: MicrophoneStream,
        firstPacket: CompletableDeferred<Unit>,
    ) {
        if (MicrophoneStream.startsWithoutDownlink(stream.audioType)) {
            val heard = withTimeoutOrNull(MicrophoneStream.SILENT_START_MILLIS) { firstPacket.await() }
            if (heard == null) {
                context.log.info("carplay.audio.microphone_silent_start", "audioType" to stream.audioType)
            }
        } else {
            firstPacket.await()
        }
        try {
            MicrophoneUplink(context, outputs.microphone).run(stream, microphone)
        } catch (e: CarPlayException) {
            // No socket for the uplink: only the microphone is lost.
            context.log.warn("carplay.audio.microphone_failed", "audioType" to stream.audioType, "error" to e.error)
        }
    }

    /**
     * One running stream: its decryption, counts and player. The player opens at the first good packet and closes with
     * the stream; a player that fails only silences it.
     */
    private inner class StreamRun(
        private val stream: AudioStreamSetup,
    ) {
        private val format = AudioFormat.fromBits(stream.audioFormat)
        private val decryptor = RealtimeAudioDecryptor(stream.key, context.cipherName)
        private var player: AudioPlayer? = null
        private var failed = false
        private var packets = 0L
        private var dropped = 0L

        /** Completed by the first good packet: the microphone's downlink start. */
        val firstPacket = CompletableDeferred<Unit>()

        /** Decrypts one datagram; the first packet starts the stream's `/feedback` position, player and microphone. */
        fun receive(datagram: ByteArray) {
            val packet = decryptor.open(datagram)
            if (packet == null) {
                if (dropped++ == 0L) {
                    context.log.warn("carplay.audio.dropped", "type" to stream.type, "bytes" to datagram.size)
                }
                return
            }
            if (packets++ == 0L) {
                starts[stream] = Start(packet.timestamp, context.time.elapsedNanos())
                context.log.info(
                    "carplay.audio.receiving",
                    "type" to stream.type,
                    "audioType" to stream.audioType,
                    "codec" to format.codec,
                    "sampleRate" to format.sampleRate,
                )
            }
            deliver(packet)
            firstPacket.complete(Unit)
        }

        @Suppress("TooGenericExceptionCaught") // A platform player's bug must silence its stream, never the session.
        private fun deliver(packet: AudioPacket) {
            if (failed) return
            try {
                val target = player ?: open()
                target.packet(packet.sequence, packet.timestamp, packet.payload())
            } catch (e: RuntimeException) {
                failed = true
                context.log.error(
                    "carplay.audio.player_failed",
                    e,
                    "type" to stream.type,
                    "audioType" to stream.audioType,
                )
                closePlayer()
            }
        }

        private fun open(): AudioPlayer {
            val info = AudioStreamInfo(stream.type, stream.audioType, format, latencyMillis(stream))
            return outputs.port.open(info).also { player = it }
        }

        /** The stream ended: its player closes and `/feedback` forgets it. */
        fun close() {
            closePlayer()
            starts.remove(stream)
            context.log.info(
                "carplay.audio.ended",
                "type" to stream.type,
                "audioType" to stream.audioType,
                "packets" to packets,
                "dropped" to dropped,
            )
        }

        @Suppress("TooGenericExceptionCaught") // Closing a broken player must not skip the stream's own cleanup.
        private fun closePlayer() {
            val closing = player ?: return
            player = null
            try {
                closing.close()
            } catch (e: RuntimeException) {
                context.log.error("carplay.audio.player_close_failed", e, "audioType" to stream.audioType)
            }
        }
    }

    /** `duckAudio` / `unduckAudio` (AirPlaySession.kt:810-811; CarPlayMediaEngine.kt:546-549). */
    override fun duck(ducked: Boolean) {
        if (ducked) {
            outputs.ducking = this
        } else if (outputs.ducking === this) {
            outputs.ducking = null
        }
        context.log.info("carplay.audio.duck", "ducked" to ducked)
        duckPort(ducked)
    }

    /** A session that ends during a prompt never sends `unduckAudio` (CarPlayMediaEngine.kt:551-553). */
    override fun close() {
        if (outputs.ducking !== this) return
        outputs.ducking = null
        context.log.info("carplay.audio.duck", "ducked" to false, "reason" to "session ended")
        duckPort(false)
    }

    @Suppress("TooGenericExceptionCaught") // A platform bug in ducking must not fail the iPhone's command.
    private fun duckPort(ducked: Boolean) {
        try {
            outputs.port.duck(ducked)
        } catch (e: RuntimeException) {
            context.log.error("carplay.audio.duck_failed", e, "ducked" to ducked)
        }
    }

    override fun feedback(stream: AudioStreamSetup): Plist.Dict {
        val clockRate = AudioFormat.fromBits(stream.audioFormat).rtpClockRate
        val entry = linkedMapOf<String, Plist>("type" to Plist.of(stream.type), "sampleRate" to Plist.of(clockRate))
        val start = starts[stream] ?: return Plist.Dict(entry)
        val nowNanos = context.time.elapsedNanos()
        val elapsedSeconds =
            max(0.0, (nowNanos - start.originNanos) / NANOS_PER_SECOND - latencyMillis(stream) / MILLIS_PER_SECOND)
        entry["streamConnectionID"] = stream.streamConnectionId
        // NTP64 values are above 2^63: their 64 bits go out as an 8-byte integer, as DiPlay's codec writes them
        // (spec D §3, deviation 2), not as Apple's 16-byte form.
        entry["timestamp"] = Plist.Integer(BigInteger.valueOf(clock.now()))
        entry["timestampRawNs"] = Plist.of(nowNanos)
        entry["sampleTime"] = Plist.of((start.firstSample + (elapsedSeconds * clockRate).roundToLong()) and U32)
        return Plist.Dict(entry)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1e9
        const val MILLIS_PER_SECOND = 1000.0
        const val U32 = 0xFFFF_FFFFL

        /** The SETUP's `audioLatencyMs`, 0 when absent (CarPlayMediaEngine.kt:203). */
        fun latencyMillis(stream: AudioStreamSetup): Int = stream.request.long("audioLatencyMs")?.toInt() ?: 0
    }
}

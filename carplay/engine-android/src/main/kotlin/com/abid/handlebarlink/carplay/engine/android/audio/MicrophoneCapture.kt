package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioRecord
import android.media.MediaRecorder
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneFormat
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.core.common.log.Logger

/** An AudioRecord, behind a seam. */
internal interface Recorder {
    val audioSessionId: Int

    /** Starts recording; false when the platform refused (its recording state says so, MicrophoneUplink.kt:174-184). */
    fun start(): Boolean

    /** A blocking read: the bytes read, 0, or a negative `AudioRecord` error. */
    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /** Stops recording; unblocks a read. Safe from another thread. */
    fun stop()

    fun release()
}

/** The platform's Opus encoder (MediaCodec), behind a seam: 20 ms of 48 kHz mono PCM in, its access units out. */
internal interface OpusEncoder {
    fun encode(pcm: ByteArray): List<ByteArray>

    fun release()
}

/** What capture builds on. Failures throw a platform `RuntimeException`. */
internal interface MicrophonePlatform {
    fun hasPermission(): Boolean

    /** `AudioRecord.getMinBufferSize` for 16-bit PCM; not positive when the format cannot record. */
    fun minBufferBytes(
        sampleRate: Int,
        channels: Int,
    ): Int

    fun recorder(
        source: Int,
        format: MicrophoneFormat,
        bufferBytes: Int,
    ): Recorder

    fun encoder(bitrate: Int): OpusEncoder

    /** The platform's echo canceller and noise suppressor on [sessionId] where available, until closed. */
    fun voiceEffects(sessionId: Int): AutoCloseable
}

/**
 * Records one stream's microphone, after DiPlay's MicrophoneUplink (MicrophoneUplink.kt:77-408): the source by audio
 * type (a call: `VOICE_COMMUNICATION` in communication mode with the platform's echo canceller and noise suppressor;
 * Siri: `VOICE_RECOGNITION`, `VOICE_COMMUNICATION` where the first cannot be built; anything else: `MIC`), a buffer of
 * at least twice the platform minimum and four frames, frames of [MicrophoneFormat.frameMillis], and each frame handed
 * to [emit] as little-endian PCM or encoded to Opus. A read error restarts the recorder once (#647); another failure
 * ends capture with a [CarPlayException]. Logs `carplay.audio.microphone_capture` and `carplay.audio.microphone_stats`.
 */
internal class MicrophoneCapture(
    private val format: MicrophoneFormat,
    private val platform: MicrophonePlatform,
    private val calls: CallAudio,
    private val log: Logger,
    private val emit: (ByteArray) -> Unit,
) {
    @Volatile private var running = true

    @Volatile private var recorder: Recorder? = null

    private val call = format.audioType == TELEPHONY
    private val samplesPerFrame =
        if (format.codec == AudioCodec.OPUS) OPUS_SAMPLES else maxOf(1, format.sampleRate * format.frameMillis / MILLIS)
    private val frameBytes = samplesPerFrame * format.channels * BYTES_PER_SAMPLE

    /** Records until [stop] or a failure; releases everything before it returns or throws. */
    fun run() {
        val minBuffer = minBuffer()
        var encoder: OpusEncoder? = null
        var effects: AutoCloseable? = null
        if (call) calls.enter(this)
        try {
            if (format.codec == AudioCodec.OPUS) encoder = platform.encoder(format.bitrate)
            val (source, built) = build(maxOf(minBuffer * 2, frameBytes * BUFFER_FRAMES))
            recorder = built
            if (call) effects = platform.voiceEffects(built.audioSessionId)
            if (!built.start() && !restart(built)) throw failure("the recorder did not start")
            log.info(
                "carplay.audio.microphone_capture",
                "audioType" to format.audioType,
                "source" to sourceName(source),
                "codec" to format.codec,
                "frameBytes" to frameBytes,
                "encoder" to (encoder != null),
            )
            capture(built, encoder)
        } finally {
            recorder?.let(::release)
            recorder = null
            effects?.close()
            encoder?.release()
            if (call) calls.leave(this)
        }
    }

    /** The platform's minimum buffer; throws without the permission or when the format cannot record. */
    private fun minBuffer(): Int {
        if (!platform.hasPermission()) throw CarPlayException(CarPlayError.PermissionMissing(RECORD_AUDIO))
        val minBuffer = platform.minBufferBytes(format.sampleRate, format.channels)
        if (minBuffer <= 0) throw failure("no recorder for ${format.sampleRate} Hz x${format.channels}")
        return minBuffer
    }

    /** Ends [run]: stops the recorder from the calling thread, which unblocks its read. */
    fun stop() {
        running = false
        recorder?.let { active ->
            try {
                active.stop()
            } catch (_: IllegalStateException) {
                // Not recording any more.
            }
        }
    }

    private fun build(bufferBytes: Int): Pair<Int, Recorder> {
        val source =
            when (format.audioType) {
                TELEPHONY -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                SIRI -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                else -> MediaRecorder.AudioSource.MIC
            }
        return try {
            source to platform.recorder(source, format, bufferBytes)
        } catch (e: UnsupportedOperationException) {
            // Some phones refuse VOICE_RECOGNITION at build(); VOICE_COMMUNICATION works (MicrophoneUplink.kt:134-142).
            if (source != MediaRecorder.AudioSource.VOICE_RECOGNITION) throw failure("recorder: $e")
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to
                platform.recorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, format, bufferBytes)
        }
    }

    private fun capture(
        active: Recorder,
        encoder: OpusEncoder?,
    ) {
        val read = ByteArray(maxOf(frameBytes, MIN_READ_BYTES))
        val frames = Framer(encoder)
        var restarted = false
        while (running) {
            val count = active.read(read, 0, read.size)
            when {
                count > 0 -> {
                    frames.add(read, count)
                }

                // A recorder with no input routed returns 0 at once (#566): pause instead of spinning.
                count == 0 -> {
                    Thread.sleep(EMPTY_READ_PAUSE_MILLIS)
                }

                running -> {
                    log.warn("carplay.audio.microphone_read_failed", "code" to count, "restarted" to restarted)
                    val again = !restarted && count != AudioRecord.ERROR_DEAD_OBJECT && restart(active)
                    if (!again) throw failure("read failed with $count")
                    restarted = true
                    frames.reset()
                }
            }
        }
    }

    /** Cuts reads into whole frames and hands each on, as PCM or as the encoder's access units. */
    private inner class Framer(
        private val encoder: OpusEncoder?,
    ) {
        private val frame = ByteArray(frameBytes)
        private var filled = 0
        private var frames = 0L
        private var bodies = 0L

        fun add(
            read: ByteArray,
            count: Int,
        ) {
            var offset = 0
            while (offset < count) {
                val copied = minOf(frame.size - filled, count - offset)
                read.copyInto(frame, filled, offset, offset + copied)
                filled += copied
                offset += copied
                if (filled == frame.size) complete()
            }
        }

        fun reset() {
            filled = 0
        }

        private fun complete() {
            filled = 0
            frames++
            val out = encoder?.encode(frame.copyOf()) ?: listOf(frame.copyOf())
            out.filter { it.isNotEmpty() }.forEach {
                bodies++
                emit(it)
            }
            if (frames % STATS_FRAMES == 0L) {
                log.info("carplay.audio.microphone_stats", "frames" to frames, "packets" to bodies)
            }
        }
    }

    private fun restart(active: Recorder): Boolean {
        try {
            active.stop()
        } catch (_: IllegalStateException) {
            // Was not recording.
        }
        return running && active.start()
    }

    private fun release(active: Recorder) {
        try {
            active.stop()
        } catch (_: IllegalStateException) {
            // Was not recording.
        }
        active.release()
    }

    private fun failure(reason: String) =
        CarPlayException(CarPlayError.Unexpected(IllegalStateException("microphone (${format.audioType}): $reason")))

    private companion object {
        const val TELEPHONY = "telephony"
        const val SIRI = "speechrecognition"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val OPUS_SAMPLES = 960
        const val MILLIS = 1000
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_FRAMES = 4
        const val MIN_READ_BYTES = 2_048
        const val STATS_FRAMES = 500L
        const val EMPTY_READ_PAUSE_MILLIS = 5L

        fun sourceName(source: Int): String =
            when (source) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "voice_communication"
                MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice_recognition"
                else -> "mic"
            }
    }
}

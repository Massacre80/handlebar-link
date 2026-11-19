package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioTrack
import com.abid.handlebarlink.carplay.engine.spi.AudioPlayer
import com.abid.handlebarlink.carplay.engine.spi.AudioStreamInfo
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.carplay.protocol.audio.AudioDecoderConfig
import com.abid.handlebarlink.carplay.protocol.audio.AudioFormat
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A PCM output track (AudioTrack in `MODE_STREAM`), behind a seam. */
internal interface PcmTrack {
    /** Frames the platform really gave the track (`getBufferCapacityInFrames`). */
    val capacityFrames: Int

    /** `getUnderrunCount`. */
    val underrunCount: Int

    /** `getPlaybackHeadPosition`, an unsigned 32-bit frame count. */
    val playbackHead: Int

    /** The performance mode the platform granted (`getPerformanceMode`). */
    val performanceMode: Int

    /** Whether the track plays on the built-in earpiece now. */
    val onEarpiece: Boolean

    /** A blocking write; the bytes written, or a negative error. */
    fun write(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    fun play()

    fun pause()

    /** `setBufferSizeInFrames`; the size the platform applied. */
    fun setBufferFrames(frames: Int): Int

    fun setVolume(volume: Float)

    fun release()
}

/** A platform decoder to 16-bit PCM (MediaCodec), behind a seam. */
internal interface PcmDecoder {
    /** Its name, for the log. */
    val name: String

    /** Queues one encoded unit, waiting briefly for an input buffer; false when none came. */
    fun queue(
        unit: ByteArray,
        presentationMicros: Long,
    ): Boolean

    /** Hands every decoded buffer ready now to [output] (bytes, offset, length; valid only during the call). */
    fun drain(output: (ByteArray, Int, Int) -> Unit)

    fun release()
}

/** What a renderer builds on. Failures throw (a platform `RuntimeException`). */
internal interface RendererPlatform {
    /** The output's own rate (`PROPERTY_OUTPUT_SAMPLE_RATE`), 0 when unknown. */
    val nativeRate: Int

    /** The output's burst (`PROPERTY_OUTPUT_FRAMES_PER_BUFFER`), 0 when unknown. */
    val burstFrames: Int

    /** `AudioTrack.getMinBufferSize`; not positive when the format cannot play. */
    fun minBufferBytes(
        sampleRate: Int,
        channels: Int,
    ): Int

    fun decoder(format: AudioFormat): PcmDecoder

    fun track(
        route: AudioRoute,
        format: AudioFormat,
        plan: PlaybackPlan,
        performanceMode: Int,
    ): PcmTrack
}

/**
 * Plays one CarPlay audio stream, after DiPlay's AudioRenderer (AndroidMediaSink.kt:1829-2864): packets from the
 * engine go into an [RtpReorderQueue] (192 packets, "holds a burst after a Wi-Fi gap"); the renderer's own thread
 * ([run]) decodes AAC-LC (as ADTS) and Opus with the platform's decoder or byte-swaps PCM, and a [TrackFeed] writes the
 * 16-bit PCM to a track with the stream's route ([AudioRoute]) at the stream's rate, music on the normal path with
 * its jitter buffer, speech on the low-latency path when its rate is the output's.
 *
 * Nothing here ever reaches the engine: a failure ends only this stream's sound and is logged. Logs
 * `carplay.audio.track` (format, route, performance mode, buffer), `carplay.audio.playing`, `carplay.audio.buffer`,
 * `carplay.audio.rebuffer`, `carplay.audio.stats` every 5 s and `carplay.audio.renderer_failed`.
 */
internal class AudioRenderer(
    private val stream: AudioStreamInfo,
    private val platform: RendererPlatform,
    private val focus: AudioFocus,
    private val calls: CallAudio,
    private val time: TimeProvider,
    private val log: Logger,
) : AudioPlayer {
    private class Packet(
        val sequence: Int,
        val timestamp: Int,
        val payload: ByteArray,
    )

    private val format = stream.format
    private val route = AudioRoute.of(stream.type, stream.audioType)
    private val queue = RtpReorderQueue<Packet>(MAX_QUEUED_PACKETS, Packet::sequence, Packet::timestamp)

    @Volatile private var running = true

    /** Set by the platform: wakes the renderer's thread when [close] is called. */
    @Volatile var onClose: () -> Unit = {}

    private val received = AtomicLong()
    private val reordered = AtomicLong()
    private val late = AtomicLong()
    private val full = AtomicLong()

    // Everything below belongs to the renderer's thread.
    private var decoder: PcmDecoder? = null
    private var track: PcmTrack? = null
    private var feed: TrackFeed? = null
    private var decoderDropped = 0L
    private var skipped = 0L
    private var statsAt = 0L
    private var statsUnderruns = 0
    private var focused = false
    private val writePcm: (ByteArray, Int, Int) -> Unit = { data, offset, length -> feed?.write(data, offset, length) }

    override fun packet(
        sequence: Int,
        timestamp: Long,
        payload: ByteArray,
    ) {
        if (!running) return
        received.incrementAndGet()
        when (queue.insert(Packet(sequence, timestamp.toInt(), payload))) {
            RtpReorderQueue.Placement.APPENDED -> {}

            RtpReorderQueue.Placement.REORDERED -> {
                reordered.incrementAndGet()
            }

            RtpReorderQueue.Placement.LATE -> {
                late.incrementAndGet()
            }

            RtpReorderQueue.Placement.FULL -> {
                full.incrementAndGet()
            }
        }
    }

    override fun close() {
        if (!running) return
        running = false
        onClose()
    }

    /** The renderer's thread: prepare, then play until closed; always releases. */
    @Suppress("TooGenericExceptionCaught") // Codec and track failures are RuntimeExceptions; any ends only this stream.
    fun run() {
        try {
            if (prepare()) {
                while (running) step(POLL_MILLIS)
            }
        } catch (_: InterruptedException) {
            // Closed while waiting.
        } catch (e: RuntimeException) {
            if (running) log.error("carplay.audio.renderer_failed", e, "audioType" to stream.audioType)
        } finally {
            logStats(ended = true)
            release()
        }
    }

    /** Builds the decoder, the track and its feed; false when this stream cannot play here. */
    fun prepare(): Boolean {
        val channels = format.channels.coerceIn(1, 2)
        val minBuffer = platform.minBufferBytes(format.sampleRate, channels)
        if (minBuffer <= 0) {
            log.warn("carplay.audio.track_unavailable", "sampleRate" to format.sampleRate, "result" to minBuffer)
            return false
        }
        if (format.codec != AudioCodec.LPCM) decoder = platform.decoder(format)
        val planned = PlaybackPlan.of(route.isMedia, format.sampleRate, channels, minBuffer, platform.burstFrames)
        val mode = route.performanceMode(format.sampleRate, platform.nativeRate)
        val built = platform.track(route, format, planned, mode).also { track = it }
        val usable = attach(built, planned)
        statsAt = time.elapsedMillis() + STATS_MILLIS
        log.info(
            "carplay.audio.track",
            "type" to stream.type,
            "audioType" to stream.audioType,
            "codec" to format.codec,
            "sampleRate" to format.sampleRate,
            "channels" to channels,
            "channel" to route.channel,
            "usage" to route.usage,
            "performance" to modeName(built.performanceMode),
            "requested" to modeName(mode),
            "nativeRate" to platform.nativeRate,
            "burst" to platform.burstFrames,
            "capacityFrames" to usable.capacityFrames,
            "bufferFrames" to usable.initialFrames,
            "startMs" to millis(usable.startFrames.toLong(), format.sampleRate),
            "decoder" to decoder?.name,
        )
        if (route.focusGain != null) {
            focus.acquire(this, route) { volume -> built.setVolume(volume) }
            focused = true
        }
        return true
    }

    /**
     * Fits [planned] to the track the platform built and starts its [TrackFeed]; the plan in use. The platform may
     * round the working size (to its bursts): the start stays one write below what it applied.
     */
    private fun attach(
        built: PcmTrack,
        planned: PlaybackPlan,
    ): PlaybackPlan {
        val fitted = planned.fitted(built.capacityFrames)
        val working = built.setBufferFrames(fitted.initialFrames).takeIf { it > 0 } ?: fitted.initialFrames
        val usable = fitted.copy(initialFrames = working).fitted(fitted.capacityFrames)
        val burst = platform.burstFrames
        val tuner =
            if (route.isMedia) {
                null
            } else {
                LatencyTuner(
                    burst,
                    fitted.capacityFrames,
                    working,
                    built.underrunCount,
                )
            }
        val sides = TrackFeed.Sides(stream.audioType, calls, time, log) { running }
        feed = TrackFeed(built, usable, route, format.sampleRate, tuner, sides)
        return usable
    }

    /** One turn of the renderer's loop: at most one packet, then decoded output, buffer care and stats. */
    fun step(waitMillis: Long) {
        queue.poll(waitMillis, TimeUnit.MILLISECONDS)?.let(::handle)
        decoder?.drain(writePcm)
        feed?.maintain(queueEmpty = queue.size == 0)
        if (time.elapsedMillis() >= statsAt) logStats(ended = false)
    }

    private fun handle(packet: Packet) {
        val payload = packet.payload
        when (format.codec) {
            AudioCodec.LPCM -> {
                writePcm(AudioDecoderConfig.swapPcm16(payload), 0, payload.size)
            }

            AudioCodec.AAC_LC -> {
                val frame = AudioDecoderConfig.adtsFrame(payload, format.sampleRate, format.channels)
                if (payload.isEmpty() || frame == null) skipped++ else decode(frame, packet.timestamp)
            }

            AudioCodec.OPUS -> {
                if (payload.size <
                    AudioDecoderConfig.MIN_OPUS_PACKET_BYTES
                ) {
                    skipped++
                } else {
                    decode(payload, packet.timestamp)
                }
            }
        }
    }

    /**
     * Queues one unit, draining the decoder's output between tries: a blocking track write can fill its output and
     * stall its input; DiPlay waits about 100 ms in all, then drops the packet (AndroidMediaSink.kt:2423-2467).
     */
    private fun decode(
        unit: ByteArray,
        timestamp: Int,
    ) {
        val codec = decoder ?: return
        val micros = (timestamp.toLong() and U32) * MICROS_PER_SECOND / format.sampleRate
        repeat(INPUT_TRIES) {
            val queued = codec.queue(unit, micros)
            codec.drain(writePcm)
            if (queued) return
        }
        decoderDropped++
    }

    private fun logStats(ended: Boolean) {
        val output = track ?: return
        val active = feed ?: return
        val underruns = output.underrunCount
        active.checkCallRoute()
        log.info(
            "carplay.audio.stats",
            "audioType" to stream.audioType,
            "channel" to route.channel,
            "rx" to received.getAndSet(0),
            "reordered" to reordered.getAndSet(0),
            "late" to late.getAndSet(0),
            "queueFull" to full.getAndSet(0),
            "decoderDropped" to decoderDropped,
            "skipped" to skipped,
            "writeErrors" to active.writeErrors,
            "underruns" to underruns - statsUnderruns,
            "queue" to queue.size,
            "playing" to active.playing,
            "trackMs" to millis(active.queuedFrames(), format.sampleRate),
            "bufferFrames" to active.bufferFrames,
            "rebuffers" to active.rebuffers,
            "ended" to ended,
        )
        statsUnderruns = underruns
        statsAt = time.elapsedMillis() + STATS_MILLIS
    }

    @Suppress("TooGenericExceptionCaught") // Releasing a broken codec or track is best effort, each on its own.
    fun release() {
        if (focused) focus.release(this)
        focused = false
        feed = null
        val parts = listOfNotNull(decoder?.let { "decoder" to it::release }, track?.let { "track" to it::release })
        decoder = null
        track = null
        parts.forEach { (part, release) ->
            try {
                release()
            } catch (e: RuntimeException) {
                log.warn("carplay.audio.release_failed", "part" to part, "error" to e.toString())
            }
        }
    }

    private companion object {
        const val MAX_QUEUED_PACKETS = 192
        const val POLL_MILLIS = 10L
        const val INPUT_TRIES = 10
        const val STATS_MILLIS = 5_000L
        const val U32 = 0xFFFF_FFFFL
        const val MICROS_PER_SECOND = 1_000_000L

        fun modeName(mode: Int): String =
            when (mode) {
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY -> "low_latency"
                AudioTrack.PERFORMANCE_MODE_POWER_SAVING -> "power_saving"
                else -> "none"
            }
    }
}

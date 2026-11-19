package com.abid.handlebarlink.carplay.engine.android.audio

import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider

/**
 * Feeds one track, on the renderer's thread (DiPlay AndroidMediaSink.kt:2540-2710): blocking writes, in
 * [PlaybackPlan.chunkBytes] while it waits to start so a paused track never fills; play() once
 * [PlaybackPlan.startFrames] are queued, or [TAIL_WAIT_MILLIS] after the last write of a short burst; a 10 ms fade-in
 * on the first PCM; music that ran dry pauses and queues its start again (rebuffer); speech grows its working size
 * through [tuner] when the device underruns with sound waiting.
 */
internal class TrackFeed(
    private val track: PcmTrack,
    private val plan: PlaybackPlan,
    private val route: AudioRoute,
    private val sampleRate: Int,
    private val tuner: LatencyTuner?,
    private val sides: Sides,
) {
    /** What the feed reports to and reads from besides its track. */
    class Sides(
        val audioType: String,
        val calls: CallAudio,
        val time: TimeProvider,
        val log: Logger,
        val running: () -> Boolean,
    )

    var playing = false
        private set
    var rebuffers = 0
        private set
    var writeErrors = 0L
        private set

    private var prebufferedBytes = 0L
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L
    private var lastWriteMillis = 0L
    private var fadeApplied = false
    private var underrunsAtStart = 0

    /** The working size now, in frames. */
    val bufferFrames: Int get() = tuner?.frames ?: plan.initialFrames

    fun write(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length <= 0) return
        if (!fadeApplied) {
            fadeIn(data, offset, length, sampleRate)
            fadeApplied = true
        }
        var written = 0
        while (written < length && sides.running()) {
            val size = if (playing) length - written else minOf(length - written, plan.chunkBytes)
            val count = track.write(data, offset + written, size)
            if (count < 0) writeErrors++
            if (count <= 0) break
            written += count
            writtenBytes += count
            lastWriteMillis = sides.time.elapsedMillis()
            if (!playing) {
                prebufferedBytes += count
                if (prebufferedBytes >= plan.startFrames.toLong() * plan.frameBytes) start()
            }
        }
    }

    /** Buffer care once per turn; [queueEmpty]: no packet waits in the reorder queue. */
    fun maintain(queueEmpty: Boolean) {
        if (rebufferDue(queueEmpty)) rebuffer()
        if (tailDue(queueEmpty)) start()
        if (playing) tune(soundWaiting = !queueEmpty)
    }

    /** Frames written but not yet played, across the head's 32-bit wrap (DiPlay AudioBufferProgress.kt). */
    fun queuedFrames(): Long {
        val head = track.playbackHead.toLong() and U32
        playedFrames += (head - lastHead) and U32
        lastHead = head
        return (writtenBytes / plan.frameBytes - playedFrames).coerceAtLeast(0)
    }

    /** Whether a call plays on the earpiece now; the call then moves to the loudspeaker (CallAudio). */
    fun checkCallRoute() {
        if (route.channel == AudioChannel.PHONE && track.onEarpiece) sides.calls.routedToEarpiece()
    }

    private fun start() {
        underrunsAtStart = track.underrunCount
        track.play()
        playing = true
        checkCallRoute()
        sides.log.info(
            "carplay.audio.playing",
            "audioType" to sides.audioType,
            "queuedMs" to millis(queuedFrames(), sampleRate),
            "rebuffers" to rebuffers,
        )
    }

    /** Music that underran, nothing left to decode, its track below half the start (AndroidMediaSink.kt:2684-2704). */
    private fun rebufferDue(queueEmpty: Boolean): Boolean {
        val starved = queueEmpty && track.underrunCount > underrunsAtStart
        return playing && route.isMedia && starved && queuedFrames() <= plan.startFrames / 2
    }

    /** Pauses without flushing and queues the start level again, counting what the track still holds. */
    private fun rebuffer() {
        track.pause()
        playing = false
        prebufferedBytes = minOf(queuedFrames(), plan.startFrames.toLong()) * plan.frameBytes
        lastWriteMillis = sides.time.elapsedMillis()
        rebuffers++
        sides.log.info("carplay.audio.rebuffer", "audioType" to sides.audioType, "count" to rebuffers)
    }

    /** A short final burst that never reaches the start plays after a bounded wait (AndroidMediaSink.kt:2705-2709). */
    private fun tailDue(queueEmpty: Boolean): Boolean {
        val waiting = !playing && prebufferedBytes > 0 && queueEmpty
        return waiting && sides.time.elapsedMillis() - lastWriteMillis >= TAIL_WAIT_MILLIS
    }

    private fun tune(soundWaiting: Boolean) {
        val frames = tuner?.observe(track.underrunCount, soundWaiting) ?: return
        val applied = track.setBufferFrames(frames)
        sides.log.info(
            "carplay.audio.buffer",
            "audioType" to sides.audioType,
            "bufferFrames" to applied,
            "bufferMs" to millis(applied.toLong(), sampleRate),
            "underruns" to track.underrunCount,
        )
    }

    private companion object {
        const val TAIL_WAIT_MILLIS = 500L
        const val U32 = 0xFFFF_FFFFL
        const val FADE_DIVISOR = 100
        const val BYTE = 0xFF
        const val BITS_PER_BYTE = 8

        /** A 10 ms linear fade over the first PCM, against a click (AndroidMediaSink.kt:2780-2790). */
        fun fadeIn(
            data: ByteArray,
            offset: Int,
            length: Int,
            sampleRate: Int,
        ) {
            val fade = minOf(length / 2, maxOf(1, sampleRate / FADE_DIVISOR))
            for (index in 0 until fade) {
                val at = offset + index * 2
                val sample = (data[at].toInt() and BYTE) or (data[at + 1].toInt() shl BITS_PER_BYTE)
                val scaled = (sample.toLong() * (index + 1) / fade).toInt()
                data[at] = scaled.toByte()
                data[at + 1] = (scaled shr BITS_PER_BYTE).toByte()
            }
        }
    }
}

/** [frames] at [sampleRate] in milliseconds. */
internal fun millis(
    frames: Long,
    sampleRate: Int,
): Long = frames * MILLIS_PER_SECOND / sampleRate

private const val MILLIS_PER_SECOND = 1000L

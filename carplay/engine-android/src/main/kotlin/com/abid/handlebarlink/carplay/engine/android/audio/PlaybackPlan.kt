package com.abid.handlebarlink.carplay.engine.android.audio

/**
 * How large one stream's track is and how much it holds before it plays, in frames (DiPlay MediaAudioBuffer.kt:11-52):
 * music queues [MEDIA_START_MILLIS] (DiPlay's wireless default) in a track of that plus [HEADROOM_MILLIS], so the
 * radio gaps of a Wi-Fi link do not interrupt it; speech (directions, Siri, calls) keeps DiPlay's small buffer: at
 * least four platform minimums and 16 KiB, started after one minimum (at least 4 KiB).
 *
 * [initialFrames] is the track's working size (`setBufferSizeInFrames`): the whole track for music; for speech only
 * what the start needs plus one prebuffer write, in whole bursts, which the [LatencyTuner] grows when the device
 * underruns (AudioTrack#setBufferSizeInFrames, #getUnderrunCount: "use a fairly large buffer, then trim").
 */
internal data class PlaybackPlan(
    val frameBytes: Int,
    val capacityFrames: Int,
    val startFrames: Int,
    val initialFrames: Int,
) {
    /** The bytes of one prebuffer write (DiPlay PREBUFFER_WRITE_CHUNK_BYTES, AndroidMediaSink.kt:2857). */
    val chunkBytes: Int get() = PREBUFFER_CHUNK_BYTES

    /**
     * The plan for the track the platform really built, [actualCapacityFrames] long: the start stays below the
     * working size less one write, or a paused full track would block before play() (MediaAudioBuffer.kt:45-52).
     */
    fun fitted(actualCapacityFrames: Int): PlaybackPlan {
        if (actualCapacityFrames <= 0) return this
        val working = initialFrames.coerceAtMost(actualCapacityFrames)
        val chunkFrames = PREBUFFER_CHUNK_BYTES / frameBytes
        val start = startFrames.coerceAtMost(working - chunkFrames).coerceAtLeast(chunkFrames)
        return copy(capacityFrames = actualCapacityFrames, startFrames = start, initialFrames = working)
    }

    companion object {
        const val MEDIA_START_MILLIS = 300
        const val HEADROOM_MILLIS = 200
        private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
        private const val MIN_START_BUFFER_BYTES = 4 * 1024
        private const val MIN_BUFFER_MULTIPLE = 4
        private const val PREBUFFER_CHUNK_BYTES = 2 * 1024
        private const val BYTES_PER_SAMPLE = 2
        private const val MILLIS_PER_SECOND = 1000L

        fun of(
            media: Boolean,
            sampleRate: Int,
            channels: Int,
            minBufferBytes: Int,
            burstFrames: Int,
        ): PlaybackPlan {
            val frameBytes = channels.coerceIn(1, 2) * BYTES_PER_SAMPLE
            val speechCapacity = maxOf(minBufferBytes * MIN_BUFFER_MULTIPLE, MIN_TRACK_BUFFER_BYTES)
            val speechStart = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES)
            if (media) {
                val bytesPerSecond = sampleRate.toLong() * frameBytes
                val start = (bytesPerSecond * MEDIA_START_MILLIS / MILLIS_PER_SECOND).toInt()
                val capacity = (bytesPerSecond * (MEDIA_START_MILLIS + HEADROOM_MILLIS) / MILLIS_PER_SECOND).toInt()
                val capacityFrames = maxOf(capacity, speechCapacity) / frameBytes
                return PlaybackPlan(frameBytes, capacityFrames, maxOf(start, speechStart) / frameBytes, capacityFrames)
            }
            val capacityFrames = speechCapacity / frameBytes
            val startFrames = speechStart / frameBytes
            val burst = burstFrames.coerceAtLeast(1)
            val needed = startFrames + PREBUFFER_CHUNK_BYTES / frameBytes
            val working = ((needed + burst - 1) / burst * burst).coerceAtMost(capacityFrames)
            return PlaybackPlan(frameBytes, capacityFrames, startFrames, working)
        }
    }
}

/**
 * Grows a speech track's working size by one burst each time the device underran while sound was waiting to be
 * written (the track was too small for this phone's scheduling, not short of network packets), up to the track's
 * capacity; it never shrinks during a stream. A gap in the iPhone's packets is not the buffer's fault: no growth.
 */
internal class LatencyTuner(
    private val burstFrames: Int,
    private val capacityFrames: Int,
    initialFrames: Int,
    initialUnderruns: Int,
) {
    var frames: Int = initialFrames
        private set
    private var underruns = initialUnderruns

    /** The device's underrun count now, and whether sound was waiting; the new working size when it grows. */
    fun observe(
        underrunCount: Int,
        soundWaiting: Boolean,
    ): Int? {
        val fresh = underrunCount > underruns
        underruns = maxOf(underruns, underrunCount)
        if (!fresh || !soundWaiting || frames >= capacityFrames) return null
        frames = (frames + burstFrames.coerceAtLeast(1)).coerceAtMost(capacityFrames)
        return frames
    }
}

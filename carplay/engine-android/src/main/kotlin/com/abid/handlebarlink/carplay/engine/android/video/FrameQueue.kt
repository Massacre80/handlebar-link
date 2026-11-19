package com.abid.handlebarlink.carplay.engine.android.video

import com.abid.handlebarlink.carplay.engine.android.video.ScreenDecoder.FrameRecycler

/** One submitted unit waiting for the decoder. */
internal sealed interface PendingUnit {
    /** A configuration, already parsed; [config] is null when it could not be. */
    class Config(
        val config: AvcConfig?,
    ) : PendingUnit

    /** A picture, whose [bytes] the decoder owns until they are recycled. */
    class Picture(
        val bytes: ByteArray,
        val offset: Int,
        val length: Int,
        /** The iPhone's frame time. */
        val ptsMicros: Long,
        /** The picture starts a reference chain (IDR). */
        val idr: Boolean,
        /** [MonotonicClock] time of the submit, for the submit-to-render latency. */
        val submittedNanos: Long,
    ) : PendingUnit
}

/** What the queue counted since the last [FrameQueue.takeCounts]. */
internal data class QueueCounts(
    val received: Int,
    /** Pictures dropped because the queue was full (the chain broke). */
    val droppedFull: Int,
    /** Pending pictures dropped because a newer keyframe made them obsolete. */
    val droppedSuperseded: Int,
    /** The most pictures that were waiting at once. */
    val highWater: Int,
)

/**
 * The hand-off from the network thread to the decoder thread, and the only state both touch. A submit never waits
 * for the decoder: it takes a short lock, and when the decoder falls behind, old pictures are dropped instead of
 * queued (a stale frame is worse than a skipped one, research-2 §5; UxPlay #376: queues behind a slow sink make the
 * latency grow without bound).
 *
 * - A keyframe (IDR) drops every picture still waiting before it: it does not need them, and showing them first would
 *   only delay it.
 * - A picture that finds the queue over its limits ([QueueLimits]) drops every waiting picture and itself, and marks
 *   the reference chain broken (DiPlay `VideoDecodeQueue.offer`: "discardFrames, then Resync"); the decoder then waits
 *   for the next keyframe and asks the iPhone for one.
 * - The latency limit is the *age* of the oldest waiting picture (how long the decoder has taken no input), not a
 *   count: a burst after a Wi-Fi stall arrives at once and is young, so it keeps its reference chain and is shown at
 *   once (DiPlay `VideoDecodeQueue`: "short delayed bursts keep their reference chain"; `VideoBacklogRecovery` judges
 *   a backlog by the waiting frame's age, 250 ms soft and 1.5 s hard). Counts and bytes are memory guards only.
 * - While a decoder starts and then drains what waited meanwhile ([decoderHasOutput]), only the 1.5 s age and the
 *   memory guards apply; the 250 ms age applies once the queue has drained to one picture. On the Poco a cold start
 *   left 12-14 pictures waiting: a 6-picture limit dropped the first IDR (before the first output, device run 1) and
 *   then broke the chain while the backlog drained (run 2, `dropped_full=11 dropped_wait_key=43 queue_max=14`).
 * - Configurations are never dropped and keep their place, so a picture is always decoded with the configuration
 *   that came before it.
 */
internal class FrameQueue(
    private val limits: QueueLimits,
    private val recycler: FrameRecycler,
    private val clock: MonotonicClock,
) {
    private val lock = Any()
    private val units = ArrayDeque<PendingUnit>()
    private var pictures = 0
    private var pictureBytes = 0L
    private var chainBroken = false
    private var received = 0
    private var droppedFull = 0
    private var droppedSuperseded = 0
    private var highWater = 0

    private var phase = Phase.STARTING

    /**
     * Set by the decoder thread: false when a decoder starts, true at its first output. The running age limit applies
     * only after that, once the queue has drained to one picture.
     */
    var decoderHasOutput: Boolean
        get() = synchronized(lock) { phase != Phase.STARTING }
        set(value) =
            synchronized(lock) {
                phase =
                    when {
                        !value -> Phase.STARTING
                        pictures <= 1 -> Phase.RUNNING
                        else -> Phase.CATCHING_UP
                    }
            }

    // Framing of the latest submitted configuration; written and read by the submitting thread only.
    private var nalLengthSize = AvcConfig.ANNEX_B

    /** See [ScreenDecoder.submit]. Returns whether the picture was queued (a configuration always is). */
    fun submit(
        frame: ByteArray,
        offset: Int,
        length: Int,
        ptsMicros: Long,
        isConfig: Boolean,
    ): Boolean {
        if (isConfig) return submitConfig(frame, offset, length)
        val idr = Avc.isIdr(frame, offset, length, nalLengthSize)
        val now = clock.nanos()
        val unit = PendingUnit.Picture(frame, offset, length, ptsMicros, idr, now)
        val dropped = ArrayList<ByteArray>()
        val queued =
            synchronized(lock) {
                received++
                if (idr) droppedSuperseded += removePictures(dropped)
                val oldest = units.firstOrNull { it is PendingUnit.Picture } as PendingUnit.Picture?
                val age = if (oldest == null) 0L else now - oldest.submittedNanos
                val maxAge = if (phase == Phase.RUNNING) limits.maxAgeNanos else limits.maxStartupAgeNanos
                if (age > maxAge || pictures >= limits.maxPictures || pictureBytes + length > limits.maxBytes) {
                    droppedFull += removePictures(dropped) + 1
                    dropped += frame
                    chainBroken = true
                    false
                } else {
                    units.addLast(unit)
                    pictures++
                    pictureBytes += length
                    highWater = maxOf(highWater, pictures)
                    true
                }
            }
        dropped.forEach(recycler::recycle)
        return queued
    }

    private fun submitConfig(
        frame: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        val config = AvcConfigParser.parse(frame, offset, length)
        if (config != null) nalLengthSize = config.nalLengthSize
        recycler.recycle(frame) // the parsed config holds its own copies
        synchronized(lock) { units.addLast(PendingUnit.Config(config)) }
        return true
    }

    /** Hands a picture's bytes back once the decoder is done with them. */
    fun recycle(picture: PendingUnit.Picture) {
        recycler.recycle(picture.bytes)
    }

    /** The next unit without taking it. */
    fun peek(): PendingUnit? = synchronized(lock) { units.firstOrNull() }

    /** Takes the next unit; the caller recycles a picture's bytes when done with them. */
    fun poll(): PendingUnit? =
        synchronized(lock) {
            units.removeFirstOrNull()?.also {
                if (it is PendingUnit.Picture) {
                    pictures--
                    pictureBytes -= it.length
                }
                if (phase == Phase.CATCHING_UP && pictures <= 1) phase = Phase.RUNNING
            }
        }

    /** Whether pictures were dropped mid-chain since the last call (the decoder must wait for a keyframe). */
    fun takeChainBroken(): Boolean =
        synchronized(lock) {
            chainBroken.also { chainBroken = false }
        }

    /** Drops every waiting picture (the surface or the decoder went away); configurations stay. */
    fun dropPictures(): Int {
        val dropped = ArrayList<ByteArray>()
        val count = synchronized(lock) { removePictures(dropped) }
        dropped.forEach(recycler::recycle)
        return count
    }

    /** The counts since the last call. */
    fun takeCounts(): QueueCounts =
        synchronized(lock) {
            QueueCounts(received, droppedFull, droppedSuperseded, highWater).also {
                received = 0
                droppedFull = 0
                droppedSuperseded = 0
                highWater = pictures
            }
        }

    /** Removes the waiting pictures under [lock], collecting their bytes for recycling outside it. */
    private fun removePictures(into: MutableList<ByteArray>): Int {
        if (pictures == 0) return 0
        var removed = 0
        val iterator = units.iterator()
        while (iterator.hasNext()) {
            val unit = iterator.next()
            if (unit is PendingUnit.Picture) {
                iterator.remove()
                into += unit.bytes
                removed++
            }
        }
        pictures = 0
        pictureBytes = 0
        return removed
    }
}

/** Where the decoder is: the queue's latency limit applies only while it runs and keeps up. */
private enum class Phase {
    /** No output yet. */
    STARTING,

    /** Output came, and what waited meanwhile is still draining. */
    CATCHING_UP,

    RUNNING,
}

/** How much may wait for the decoder before the waiting pictures are dropped. */
internal data class QueueLimits(
    /** Memory guard (DiPlay `VideoDecodeQueue`: 60 frames). */
    val maxPictures: Int,
    /** Memory guard (DiPlay `VideoDecodeQueue`: 8 MiB). */
    val maxBytes: Long,
    /** Oldest waiting picture's age while the decoder runs (DiPlay `VideoBacklogRecovery` SOFT_AGE_NS, 250 ms). */
    val maxAgeNanos: Long,
    /** The same while a decoder starts and drains its backlog (DiPlay HARD_AGE_NS, 1.5 s). */
    val maxStartupAgeNanos: Long,
)

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
 * - A picture that finds [maxPictures] or [maxBytes] already waiting drops every waiting picture and itself, and marks
 *   the reference chain broken (DiPlay `VideoDecodeQueue.offer`: "discardFrames, then Resync"); the decoder then waits
 *   for the next keyframe and asks the iPhone for one.
 * - While the decoder is [starting] (no output yet) only [maxBytes] applies: everything from the last keyframe on waits
 *   for it. On the Poco a cold start took about 200 ms on the decoder thread, 12 pictures at 60 fps; the 6-picture
 *   limit then dropped the first keyframe and the picture stayed black until the next one, a second later (device
 *   run 2026-11-19, `carplay.video.stats` dropped_full=7 dropped_wait_key=53).
 * - Configurations are never dropped and keep their place, so a picture is always decoded with the configuration
 *   that came before it.
 */
internal class FrameQueue(
    private val maxPictures: Int,
    private val maxBytes: Long,
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

    /** Set by the decoder thread: no decoder output yet, so the picture limit does not apply. */
    @Volatile var starting = true

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
        val unit = PendingUnit.Picture(frame, offset, length, ptsMicros, idr, clock.nanos())
        val dropped = ArrayList<ByteArray>()
        val queued =
            synchronized(lock) {
                received++
                if (idr) droppedSuperseded += removePictures(dropped)
                if ((!starting && pictures >= maxPictures) || pictureBytes + length > maxBytes) {
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

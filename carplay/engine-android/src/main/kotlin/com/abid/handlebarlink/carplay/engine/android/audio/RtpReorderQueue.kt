package com.abid.handlebarlink.carplay.engine.android.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded queue of RTP packets in sequence order, ported from DiPlay's RtpReorderQueue.kt:10-149. Wi-Fi delivers some
 * packets after later ones; played in arrival order, each makes the sound jump. A late packet goes back to its place
 * while a later packet is still queued; one whose place was already taken, or a duplicate, is not queued. When the
 * queue is full the arriving packet is not queued, whatever its place. The engine inserts, the player's thread polls.
 */
internal class RtpReorderQueue<T : Any>(
    private val capacity: Int,
    private val sequenceOf: (T) -> Int,
    private val timestampOf: (T) -> Int,
) {
    enum class Placement { APPENDED, REORDERED, LATE, FULL }

    private class Entry<T>(
        val item: T,
        val sequence: Int,
        val timestamp: Int,
        val run: Int,
    )

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val entries = ArrayList<Entry<T>>()

    // A run is the packets since the last restart or sequence discontinuity; only they are put in order.
    private var run = 0
    private var newest: Entry<T>? = null
    private var lastTaken: Entry<T>? = null

    val size: Int get() = lock.withLock { entries.size }

    fun insert(item: T): Placement {
        val sequence = sequenceOf(item)
        val timestamp = timestampOf(item)
        return lock.withLock {
            val latest = newest
            val behind = latest != null && order(sequence, timestamp, latest) <= 0
            when {
                entries.size >= capacity -> {
                    Placement.FULL
                }

                behind && isLate(sequence, timestamp, checkNotNull(latest)) -> {
                    insertLate(item, sequence, timestamp)
                }

                else -> {
                    // Behind the newest by more than a reordering: the iPhone restarted its sequence.
                    if (behind) startRun()
                    val entry = Entry(item, sequence, timestamp, run)
                    entries.add(entry)
                    newest = entry
                    notEmpty.signal()
                    Placement.APPENDED
                }
            }
        }
    }

    /** The next packet starts a new sequence: none is dropped as late against the packets before it. */
    fun restartSequence() {
        lock.withLock { startRun() }
    }

    /** The first packet, waiting up to [timeout]; null when none came. */
    fun poll(
        timeout: Long,
        unit: TimeUnit,
    ): T? {
        var nanos = unit.toNanos(timeout)
        lock.withLock {
            while (entries.isEmpty()) {
                if (nanos <= 0L) return null
                nanos = notEmpty.awaitNanos(nanos)
            }
            return takeFirst()
        }
    }

    /** The first packet now, or null. */
    fun poll(): T? = lock.withLock { takeFirst() }

    private fun startRun() {
        run++
        newest = null
        lastTaken = null
    }

    private fun takeFirst(): T? {
        val entry = entries.removeFirstOrNull()
        if (entry?.run == run) lastTaken = entry
        return entry?.item
    }

    /** Behind the newest packet by a reordering, not by a restarted sequence or timestamp. */
    private fun isLate(
        sequence: Int,
        timestamp: Int,
        latest: Entry<T>,
    ): Boolean {
        val behind = -sequenceDelta(sequence, latest.sequence)
        return behind in 0..MAX_REORDER_PACKETS && timestamp - latest.timestamp <= 0
    }

    private fun insertLate(
        item: T,
        sequence: Int,
        timestamp: Int,
    ): Placement {
        val taken = lastTaken
        val index =
            if (taken != null &&
                order(sequence, timestamp, taken) <= 0
            ) {
                null
            } else {
                lateIndex(sequence, timestamp)
            }
        if (index == null) return Placement.LATE
        entries.add(index, Entry(item, sequence, timestamp, run))
        notEmpty.signal()
        return Placement.REORDERED
    }

    /** Where a late packet goes among the queued ones of this run, or null when it is already queued. */
    private fun lateIndex(
        sequence: Int,
        timestamp: Int,
    ): Int? {
        var index = entries.size
        while (index > 0) {
            val queued = entries[index - 1]
            val after = if (queued.run == run) order(sequence, timestamp, queued) else 1
            if (after == 0) return null
            if (after > 0) break
            index--
        }
        return index
    }

    /** Positive when the packet comes after [other], negative before it, 0 for the same packet. */
    private fun order(
        sequence: Int,
        timestamp: Int,
        other: Entry<T>,
    ): Int {
        val bySequence = sequenceDelta(sequence, other.sequence)
        if (bySequence != 0) return bySequence
        return (timestamp - other.timestamp).coerceIn(-1, 1)
    }

    companion object {
        /** Further behind, a packet is a restarted sequence rather than a late one (RtpReorderQueue.kt:140). */
        const val MAX_REORDER_PACKETS = 64
    }
}

/** How far [sequence] is after [other] on the 16-bit RTP sequence circle, -32 768..32 767. */
private fun sequenceDelta(
    sequence: Int,
    other: Int,
): Int = ((sequence - other) shl SEQUENCE_SHIFT) shr SEQUENCE_SHIFT

private const val SEQUENCE_SHIFT = 16

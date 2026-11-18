package com.abid.handlebarlink.carplay.protocol.iap2

/**
 * Our outgoing data packets: those waiting for window space and those sent but not yet acknowledged.
 * Unlike DiPlay (spec A §8.6-8.10): at most `maxOutgoing` are outstanding, the retransmit timer
 * follows the oldest deadline, acknowledgements are judged against what was actually sent, and a
 * packet is retransmitted `maxRetransmissions` times before the link fails.
 */
internal class SendWindow(
    initialSequence: Int,
) {
    class Packet(
        val sequence: Int,
        val sessionId: Int,
        val payload: ByteArray,
        var deadline: Long,
        var retransmissions: Int = 0,
    )

    private class Waiting(
        val sessionId: Int,
        val payload: ByteArray,
    )

    private val waiting = ArrayDeque<Waiting>()
    private val unacknowledged = ArrayDeque<Packet>()

    /** The last sequence number used (the SYN's at first). */
    var lastSent: Int = initialSequence
        private set

    /** The highest sequence the peer has acknowledged. */
    private var acknowledged: Int = initialSequence

    val outstanding: Int get() = sequenceDistance(lastSent, acknowledged)

    val hasWaiting: Boolean get() = waiting.isNotEmpty()

    /** The earliest retransmit deadline, or null when nothing is outstanding. */
    val nextDeadline: Long? get() = unacknowledged.minOfOrNull { it.deadline }

    fun enqueue(
        sessionId: Int,
        payload: ByteArray,
    ) {
        if (waiting.size >= MAX_WAITING) throw Iap2LinkException("iAP2 send queue full ($MAX_WAITING packets)")
        waiting.addLast(Waiting(sessionId, payload))
    }

    /**
     * Takes waiting packets while the window has room; each gets the next sequence number. A peer that
     * advertises a window of 0 still gets one packet at a time, as DiPlay sends it (Iap2LinkEngine.kt:478).
     */
    fun release(
        maxOutgoing: Int,
        deadline: Long,
        track: Boolean,
    ): List<Packet> =
        buildList {
            while (waiting.isNotEmpty() && outstanding < maxOf(maxOutgoing, 1)) {
                val next = waiting.removeFirst()
                lastSent = nextSequence(lastSent)
                val packet = Packet(lastSent, next.sessionId, next.payload, deadline)
                // A peer that asks for no retransmissions counts every packet as acknowledged on send.
                if (track) unacknowledged.addLast(packet) else acknowledged = lastSent
                add(packet)
            }
        }

    /** Applies a cumulative acknowledgement; ignores one for a packet we never sent. */
    fun acknowledge(acknowledgement: Int) {
        if (sequenceDistance(acknowledgement, acknowledged) > outstanding) return
        acknowledged = acknowledgement
        // Still outstanding: sequences 1..outstanding after the acknowledgement; everything else is acknowledged.
        while (unacknowledged.isNotEmpty() && !isOutstanding(unacknowledged.first().sequence)) {
            unacknowledged.removeFirst()
        }
    }

    private fun isOutstanding(sequence: Int): Boolean = sequenceDistance(sequence, acknowledged) in 1..outstanding

    /** Packets whose deadline has passed, oldest first. */
    fun due(now: Long): List<Packet> = unacknowledged.filter { it.deadline <= now }

    /** Unacknowledged packets with the given sequence numbers (an EAK's list). */
    fun withSequences(sequences: Set<Int>): List<Packet> = unacknowledged.filter { it.sequence in sequences }

    private companion object {
        /** DiPlay's queue limit (Iap2LinkEngine.kt:727). */
        const val MAX_WAITING = 64
    }
}

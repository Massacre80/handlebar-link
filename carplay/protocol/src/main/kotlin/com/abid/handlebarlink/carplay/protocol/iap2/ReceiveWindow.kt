package com.abid.handlebarlink.carplay.protocol.iap2

/** Puts the iPhone's data packets back in order (DiPlay Iap2LinkEngine.kt:427-474, spec A §5.6). */
internal class ReceiveWindow {
    sealed interface Result {
        /** In order: these frames, followed by any buffered ones that are now contiguous. */
        class Delivered(
            val frames: List<Iap2Frame>,
        ) : Result

        /** Already received (our acknowledgement was lost): acknowledge again. */
        data object Duplicate : Result

        /** Ahead of a gap; kept until the gap fills. */
        data object Buffered : Result

        /** Ahead of a gap of at least a window: ask for [missing] with an EAK. */
        class Gap(
            val missing: List<Int>,
        ) : Result
    }

    /** The last sequence delivered in order (the peer's SYN sequence at first). */
    var lastInOrder: Int = 0
        private set

    private val buffered = HashMap<Int, Iap2Frame>()

    fun reset(sequence: Int) {
        lastInOrder = sequence
        buffered.clear()
    }

    fun accept(
        frame: Iap2Frame,
        window: Int,
    ): Result {
        val distance = sequenceDistance(frame.sequence, lastInOrder)
        return when {
            distance == 0 || distance > window + SLACK -> Result.Duplicate
            distance > 1 -> holdAhead(frame, distance, window)
            else -> deliver(frame)
        }
    }

    private fun holdAhead(
        frame: Iap2Frame,
        distance: Int,
        window: Int,
    ): Result {
        val fresh = frame.sequence !in buffered
        if (fresh) {
            if (buffered.size >= MAX_BUFFERED) throw Iap2LinkException("iAP2 out-of-order buffer full")
            buffered[frame.sequence] = frame
        }
        return if (fresh && distance >= window) {
            Result.Gap((1 until distance).map { (lastInOrder + it) and 0xFF })
        } else {
            Result.Buffered
        }
    }

    private fun deliver(frame: Iap2Frame): Result.Delivered {
        val delivered = mutableListOf(frame)
        lastInOrder = frame.sequence
        while (true) {
            val next = buffered.remove(nextSequence(lastInOrder)) ?: break
            delivered += next
            lastInOrder = next.sequence
        }
        return Result.Delivered(delivered)
    }

    private companion object {
        /** Tolerance beyond the window before a packet is old (DiPlay ACK_SLACK, Iap2LinkEngine.kt:429, :685). */
        const val SLACK = 10

        /** DiPlay's limit (Iap2LinkEngine.kt:728). */
        const val MAX_BUFFERED = 64
    }
}

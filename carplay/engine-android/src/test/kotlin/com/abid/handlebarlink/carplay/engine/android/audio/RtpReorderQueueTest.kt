package com.abid.handlebarlink.carplay.engine.android.audio

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.engine.android.audio.RtpReorderQueue.Placement
import org.junit.Test
import java.util.concurrent.TimeUnit

/** DiPlay's RtpReorderQueueTest.kt cases (AAC: 1024 samples per packet), plus the reordering limit's boundary. */
class RtpReorderQueueTest {
    private data class Packet(
        val sequence: Int,
        val sample: Int,
    )

    private fun queue(capacity: Int = 192) = RtpReorderQueue(capacity, Packet::sequence, Packet::sample)

    private fun packet(
        sequence: Int,
        base: Long = 0L,
    ) = Packet(sequence and 0xffff, (base + sequence.toLong() * 1024).toInt())

    private fun RtpReorderQueue<Packet>.drain(): List<Int> = generateSequence { poll() }.map { it.sequence }.toList()

    @Test
    fun `a packet up to seven behind goes back to its place`() {
        for (behind in 1..7) {
            val queue = queue()
            val arrival = (0..20).toMutableList().apply { add(5 + behind, removeAt(5)) }
            val placements = arrival.map { queue.insert(packet(it)) }
            assertThat(placements.count { it == Placement.REORDERED }).isEqualTo(1)
            assertThat(queue.drain()).isEqualTo((0..20).toList())
        }
    }

    @Test
    fun `several late packets in one burst are all restored`() {
        val queue = queue()
        listOf(0, 1, 4, 2, 6, 3, 5, 9, 7, 8, 10).forEach { queue.insert(packet(it)) }
        assertThat(queue.drain()).isEqualTo((0..10).toList())
    }

    @Test
    fun `a late packet whose next was already taken is dropped, one whose next waits goes first`() {
        val queue = queue()
        listOf(1, 3, 5).forEach { queue.insert(packet(it)) }
        assertThat(queue.poll()?.sequence).isEqualTo(1)
        assertThat(queue.insert(packet(2))).isEqualTo(Placement.REORDERED)
        assertThat(queue.poll()?.sequence).isEqualTo(2)
        assertThat(queue.poll()?.sequence).isEqualTo(3)
        assertThat(queue.insert(packet(2))).isEqualTo(Placement.LATE)
        assertThat(queue.drain()).containsExactly(5)
    }

    @Test
    fun `duplicates are dropped, queued or taken`() {
        val queue = queue()
        listOf(5, 6, 7).forEach { queue.insert(packet(it)) }
        assertThat(queue.insert(packet(7))).isEqualTo(Placement.LATE)
        assertThat(queue.insert(packet(6))).isEqualTo(Placement.LATE)
        assertThat(queue.poll()?.sequence).isEqualTo(5)
        assertThat(queue.insert(packet(5))).isEqualTo(Placement.LATE)
        assertThat(queue.drain()).containsExactly(6, 7)
    }

    @Test
    fun `sequence and timestamp wrap keep the order`() {
        val queue = queue()
        val base = 0xffff_f000L - 65_533L * 1024
        listOf(65_533, 65_534, 65_536, 65_535, 65_538, 65_537).forEach { queue.insert(packet(it, base)) }
        assertThat(queue.drain()).containsExactly(65_533, 65_534, 65_535, 0, 1, 2)
        assertThat(queue.insert(packet(65_535, base))).isEqualTo(Placement.LATE)
    }

    @Test
    fun `sixty-four behind is late, sixty-five behind starts a new sequence`() {
        val queue = queue()
        queue.insert(packet(100))
        assertThat(queue.insert(packet(36))).isEqualTo(Placement.REORDERED)
        queue.drain()
        queue.insert(packet(200))
        assertThat(queue.insert(packet(135))).isEqualTo(Placement.APPENDED)
    }

    @Test
    fun `a restarted sequence is never dropped as late`() {
        val queue = queue()
        (1000..1010).forEach { queue.insert(packet(it)) }
        queue.drain()
        assertThat(queue.insert(packet(1005))).isEqualTo(Placement.LATE)
        queue.restartSequence()
        (1001..1003).forEach { assertThat(queue.insert(packet(it))).isEqualTo(Placement.APPENDED) }
        assertThat(queue.drain()).containsExactly(1001, 1002, 1003)
    }

    @Test
    fun `a full queue refuses the arriving packet and a timed poll returns null when empty`() {
        val queue = queue(capacity = 2)
        queue.insert(packet(1))
        queue.insert(packet(2))
        assertThat(queue.insert(packet(3))).isEqualTo(Placement.FULL)
        assertThat(queue.size).isEqualTo(2)
        queue.drain()
        assertThat(queue.poll(1, TimeUnit.MILLISECONDS)).isNull()
    }
}

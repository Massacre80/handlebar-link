package com.abid.handlebarlink.carplay.engine.android.video

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

class FrameQueueTest {
    private val recycler = RecordingRecycler()
    private val clock = ManualClock()
    private val limits =
        QueueLimits(
            maxPictures = 3,
            maxBytes = 1_000,
            maxAgeNanos = 250 * MS,
            maxStartupAgeNanos =
                1_500 * MS,
        )
    private val queue =
        FrameQueue(limits, recycler, clock).apply {
            decoderHasOutput = true // a running decoder; starting and catching up have their own tests
        }
    private val avcc = vector("ffmpeg-64x64.avcc")

    @Test
    fun `a picture that finds the queue full drops every waiting picture and itself, and breaks the chain`() {
        queue.submit(avcc, 0, avcc.size, 0, isConfig = true)
        val waiting = List(3) { p(it) }
        waiting.forEach { queue.submit(it, 0, it.size, 0, isConfig = false) }
        val late = p(9)

        val queued = queue.submit(late, 0, late.size, 0, isConfig = false)

        assertThat(queued).isFalse()
        assertThat(queue.takeChainBroken()).isTrue()
        assertThat(queue.takeChainBroken()).isFalse()
        (waiting + late).forEach { assertThat(recycler.count(it)).isEqualTo(1) }
        assertThat(queue.poll() is PendingUnit.Config).isTrue() // the configuration stays
        assertThat(queue.poll()).isNull()
        assertThat(queue.takeCounts().droppedFull).isEqualTo(4)
    }

    @Test
    fun `a running decoder that takes no input for longer than the age limit has its backlog dropped`() {
        val waiting = p(1)
        queue.submit(waiting, 0, waiting.size, 0, isConfig = false)
        clock.advanceMillis(250)
        val inTime = p(2)
        assertThat(queue.submit(inTime, 0, inTime.size, 0, isConfig = false)).isTrue()
        clock.advanceMillis(1)
        val late = p(3)

        assertThat(queue.submit(late, 0, late.size, 0, isConfig = false)).isFalse()
        assertThat(queue.takeChainBroken()).isTrue()
        listOf(waiting, inTime, late).forEach { assertThat(recycler.count(it)).isEqualTo(1) }
    }

    @Test
    fun `a burst that arrives at once keeps its reference chain`() {
        clock.advanceMillis(500) // a Wi-Fi stall: nothing waits meanwhile
        val burst = List(3) { p(it) }

        val queued = burst.map { queue.submit(it, 0, it.size, 0, isConfig = false) }

        assertThat(queued.all { it }).isTrue()
        assertThat(queue.takeChainBroken()).isFalse()
    }

    @Test
    fun `while a decoder starts pictures may wait up to the startup age`() {
        val roomy = FrameQueue(limits.copy(maxPictures = 1_000), recycler, clock) // decoderHasOutput false: starting
        roomy.submit(idr(0), 0, 8, 0, isConfig = false)
        clock.advanceMillis(1_500)
        assertThat(roomy.submit(p(1), 0, 8, 0, isConfig = false)).isTrue()
        clock.advanceMillis(1)

        assertThat(roomy.submit(p(2), 0, 8, 0, isConfig = false)).isFalse()
        assertThat(roomy.takeChainBroken()).isTrue()
    }

    @Test
    fun `after the first output the backlog drains before the running age limit applies`() {
        val roomy = FrameQueue(limits.copy(maxPictures = 1_000), recycler, clock)
        repeat(6) {
            roomy.submit(if (it == 0) idr(0) else p(it), 0, 8, 0, isConfig = false)
            clock.advanceMillis(16)
        }
        clock.advanceMillis(300) // a cold start
        roomy.decoderHasOutput = true // six still wait: catching up

        assertThat(roomy.submit(p(10), 0, 8, 0, isConfig = false)).isTrue() // the oldest waited 396 ms

        repeat(6) { roomy.poll() } // drained to one picture: running
        clock.advanceMillis(251)
        assertThat(roomy.submit(p(11), 0, 8, 0, isConfig = false)).isFalse()
        assertThat(roomy.takeChainBroken()).isTrue()
    }

    @Test
    fun `the byte limit counts like the picture limit`() {
        val big = p(1, size = 600)
        queue.submit(big, 0, big.size, 0, isConfig = false)
        val next = p(2, size = 500)

        assertThat(queue.submit(next, 0, next.size, 0, isConfig = false)).isFalse()
        assertThat(queue.takeChainBroken()).isTrue()
        assertThat(queue.peek()).isNull()
    }

    @Test
    fun `a keyframe drops the pictures waiting before it but keeps configurations and the chain`() {
        queue.submit(avcc, 0, avcc.size, 0, isConfig = true)
        val old = listOf(idr(0), p(1))
        old.forEach { queue.submit(it, 0, it.size, 0, isConfig = false) }
        queue.submit(avcc, 0, avcc.size, 0, isConfig = true)
        val key = idr(2)

        assertThat(queue.submit(key, 0, key.size, 0, isConfig = false)).isTrue()

        assertThat(queue.takeChainBroken()).isFalse()
        old.forEach { assertThat(recycler.count(it)).isEqualTo(1) }
        assertThat(queue.poll() is PendingUnit.Config).isTrue()
        assertThat(queue.poll() is PendingUnit.Config).isTrue()
        assertThat((queue.poll() as PendingUnit.Picture).bytes).isEqualTo(key)
        assertThat(queue.takeCounts().droppedSuperseded).isEqualTo(2)
    }

    @Test
    fun `keyframes are recognised with the framing of the latest configuration`() {
        val key = idr(0)
        queue.submit(key, 0, key.size, 0, isConfig = false) // before any config: Annex B is assumed
        assertThat((queue.poll() as PendingUnit.Picture).idr).isFalse()

        queue.submit(avcc, 0, avcc.size, 0, isConfig = true)
        queue.submit(key, 0, key.size, 0, isConfig = false)

        assertThat(queue.poll() is PendingUnit.Config).isTrue()
        assertThat((queue.poll() as PendingUnit.Picture).idr).isTrue()
    }

    @Test
    fun `a configuration is parsed and handed back at once`() {
        val config = avcc.copyOf()

        queue.submit(config, 0, config.size, 0, isConfig = true)

        assertThat(recycler.count(config)).isEqualTo(1)
        assertThat((queue.poll() as PendingUnit.Config).config).isNotNull()
    }

    @Test
    fun `units come out in the order they went in`() {
        queue.submit(avcc, 0, avcc.size, 0, isConfig = true)
        val a = idr(1)
        val b = p(2)
        queue.submit(a, 0, a.size, 11, isConfig = false)
        queue.submit(b, 0, b.size, 22, isConfig = false)

        val order = generateSequence { queue.poll() }.map { (it as? PendingUnit.Picture)?.ptsMicros }.toList()

        assertThat(order).containsExactly(null, 11L, 22L)
        assertThat(queue.takeCounts().highWater).isEqualTo(2)
    }

    @Test
    fun `dropping the waiting pictures recycles them and keeps configurations`() {
        queue.submit(avcc, 0, avcc.size, 0, isConfig = true)
        val a = p(1)
        queue.submit(a, 0, a.size, 0, isConfig = false)

        assertThat(queue.dropPictures()).isEqualTo(1)

        assertThat(recycler.count(a)).isEqualTo(1)
        assertThat(queue.poll() is PendingUnit.Config).isTrue()
        assertThat(queue.poll()).isNull()
    }
}

/** A length-prefixed IDR picture, tagged so pictures differ. */
internal fun idr(
    tag: Int,
    size: Int = 8,
): ByteArray = lengthPrefixed(0x65, tag, size)

/** A length-prefixed P picture (non-IDR slice). */
internal fun p(
    tag: Int,
    size: Int = 8,
): ByteArray = lengthPrefixed(0x41, tag, size)

private fun lengthPrefixed(
    header: Int,
    tag: Int,
    size: Int,
): ByteArray {
    val nal = ByteArray(size - 4) { (tag + it).toByte() }.also { it[0] = header.toByte() }
    return byteArrayOf(0, 0, (nal.size shr 8).toByte(), nal.size.toByte()) + nal
}

private const val MS = 1_000_000L

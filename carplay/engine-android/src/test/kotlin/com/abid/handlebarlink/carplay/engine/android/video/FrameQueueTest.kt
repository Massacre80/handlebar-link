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
    private val queue =
        FrameQueue(maxPictures = 3, maxBytes = 1_000, recycler = recycler, clock = ManualClock()).apply {
            starting = false // a running decoder; the start-up case has its own test
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
    fun `while the decoder is starting only the byte limit applies`() {
        queue.starting = true
        val pictures = listOf(idr(0)) + List(9) { p(it + 1) }

        val queued = pictures.map { queue.submit(it, 0, it.size, 0, isConfig = false) }

        assertThat(queued.all { it }).isTrue()
        assertThat(queue.takeChainBroken()).isFalse()
        assertThat(generateSequence { queue.poll() }.toList().size).isEqualTo(10)
        val big = p(20, size = 1_001)
        assertThat(queue.submit(big, 0, big.size, 0, isConfig = false)).isFalse()
        assertThat(queue.takeChainBroken()).isTrue()
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

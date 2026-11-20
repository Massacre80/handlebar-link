package com.abid.handlebarlink.carplay.engine.android.video

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import org.junit.Test

class PictureBuffersTest {
    @Test
    fun `a buffer given back is handed out again for any picture it fits`() {
        val buffers = PictureBuffers()
        val first = buffers.take(20_000)
        buffers.give(first)

        assertThat(buffers.take(12_000)).isSameInstanceAs(first)
        assertThat(buffers.allocated).isEqualTo(1)
    }

    @Test
    fun `a new buffer has headroom, so the next slightly larger picture still fits`() {
        val buffers = PictureBuffers()
        val first = buffers.take(20_000)
        buffers.give(first)

        assertThat(first.size).isGreaterThanOrEqualTo(22_500)
        assertThat(buffers.take(22_000)).isSameInstanceAs(first)
    }

    @Test
    fun `a picture larger than every kept buffer gets a new one`() {
        val buffers = PictureBuffers()
        val small = buffers.take(10_000)
        buffers.give(small)

        assertThat(buffers.take(300_000)).isNotSameInstanceAs(small)
        assertThat(buffers.allocated).isEqualTo(2)
    }

    @Test
    fun `when full the larger buffers are kept, and a configuration's tiny array is never kept`() {
        val buffers = PictureBuffers(maxKept = 1)
        val small = ByteArray(8_192)
        val large = ByteArray(65_536)
        buffers.give(small)
        buffers.give(large)
        buffers.give(ByteArray(40)) // An SPS and PPS the port built.

        assertThat(buffers.take(1)).isSameInstanceAs(large)
        assertThat(buffers.allocated).isEqualTo(0)
    }

    @Test
    fun `a buffer given back twice is kept once`() {
        val buffers = PictureBuffers()
        val buffer = buffers.take(20_000)
        buffers.give(buffer)
        buffers.give(buffer)

        buffers.take(20_000)
        assertThat(buffers.take(20_000)).isNotSameInstanceAs(buffer)
    }
}

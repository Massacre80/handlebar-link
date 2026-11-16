package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import org.junit.Test

class SabFrameTest {
    @Test
    fun `frame keeps its own copy of the bytes it was built from`() {
        val source = hex("01 02")
        val frame = SabFrame(source)

        source[0] = 0x7F

        assertThat(frame.toString()).isEqualTo("01 02")
    }

    @Test
    fun `changing the array handed out does not change the frame`() {
        val frame = SabFrame(hex("01 02"))

        frame.toByteArray()[0] = 0x7F

        assertThat(frame[0]).isEqualTo(0x01.toByte())
    }

    @Test
    fun `frames with the same bytes are equal and hash alike`() {
        assertThat(SabFrame(hex("0A 00"))).isEqualTo(SabFrame(hex("0A 00")))
        assertThat(SabFrame(hex("0A 00")).hashCode()).isEqualTo(SabFrame(hex("0A 00")).hashCode())
        assertThat(SabFrame(hex("0A 00"))).isNotEqualTo(SabFrame(hex("0A 01")))
    }

    @Test
    fun `contentEquals compares with raw bytes`() {
        assertThat(SabFrame(hex("03")).contentEquals(hex("03"))).isTrue()
        assertThat(SabFrame(hex("03")).contentEquals(hex("03 00"))).isFalse()
    }

    @Test
    fun `hex is upper case with spaces, as in the spec`() {
        assertThat(hex("00 0a ff 7f 80").toSabHex()).isEqualTo("00 0A FF 7F 80")
        assertThat(ByteArray(0).toSabHex()).isEqualTo("")
    }
}

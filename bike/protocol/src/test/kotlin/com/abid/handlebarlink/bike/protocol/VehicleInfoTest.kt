package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

class VehicleInfoTest {
    @Test
    fun `the CB350's series code bytes F5 03 read as 03F5`() {
        // Read from the real meter on 2026-11-16.
        val code = SeriesCode.decode(hex("F5 03"))

        assertThat(code?.hex).isEqualTo("03F5")
        assertThat(code?.isCb350).isEqualTo(true)
    }

    @Test
    fun `other series codes are not a CB350`() {
        val code = SeriesCode.decode(hex("65 03"))

        assertThat(code?.hex).isEqualTo("0365")
        assertThat(code?.isCb350).isEqualTo(false)
    }

    @Test
    fun `series codes must be exactly two bytes`() {
        assertThat(SeriesCode.decode(hex("F5"))).isNull()
        assertThat(SeriesCode.decode(hex("F5 03 00"))).isNull()
        assertThat(SeriesCode.decode(ByteArray(0))).isNull()
    }

    @Test
    fun `the CB350 reports indicator icons only`() {
        // Display capability 1, read from the real meter.
        assertThat(DisplayCapability.decode(hex("01")))
            .isEqualTo(DisplayCapability(indicator = true, popUp = false, mysteryBox = false))
    }

    @Test
    fun `each capability bit is read on its own`() {
        val all = DisplayCapability.decode(hex("07"))

        assertThat(all).isEqualTo(DisplayCapability(indicator = true, popUp = true, mysteryBox = true))
        assertThat(DisplayCapability.decode(hex("08"))?.indicator).isEqualTo(false)
        assertThat(DisplayCapability.decode(ByteArray(0))).isNull()
    }

    @Test
    fun `firmware version prints as two-digit major and minor`() {
        assertThat(FirmwareVersion.decode(hex("01 05")).toString()).isEqualTo("01.05")
        assertThat(FirmwareVersion.decode(hex("0F 0F")).toString()).isEqualTo("15.15")
        assertThat(FirmwareVersion.decode(hex("01"))).isNull()
    }

    @Test
    fun `capability flags are independent`() {
        val popUpOnly = DisplayCapability.decode(hex("02"))

        assertThat(popUpOnly?.indicator == true).isFalse()
        assertThat(popUpOnly?.popUp == true).isTrue()
    }
}

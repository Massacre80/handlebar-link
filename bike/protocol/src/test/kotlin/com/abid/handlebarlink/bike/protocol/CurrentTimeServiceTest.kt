package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Byte for byte as RoadSync's CTSData builds them (SAB_PROTOCOL.md §4.6). */
class CurrentTimeServiceTest {
    @Test
    fun `current time is ten little-endian bytes`() {
        // Monday 2026-11-16 21:58:30.999: year 2026 = 0x07EA.
        val time = LocalDateTime.of(2026, 11, 16, 21, 58, 30, 999_000_000)

        val frame = CurrentTimeService.currentTime(time, AdjustReason.NONE)

        // fraction = (999 * 0.255f).toInt() = 254 = 0xFE
        assertThat(frame.toString()).isEqualTo("EA 07 0B 10 15 3A 1E 01 FE 00")
    }

    @Test
    fun `day of week runs from 1 Monday to 7 Sunday`() {
        val sunday = LocalDateTime.of(2026, 11, 22, 0, 0)

        assertThat(CurrentTimeService.currentTime(sunday, AdjustReason.NONE)[7]).isEqualTo(7.toByte())
    }

    @Test
    fun `the fraction uses RoadSync's float formula`() {
        val halfSecond = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 500_000_000)
        val onTheSecond = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 0)

        // 500 * 0.255f = 127.5 → 127
        assertThat(CurrentTimeService.currentTime(halfSecond, AdjustReason.NONE)[8]).isEqualTo(127.toByte())
        assertThat(CurrentTimeService.currentTime(onTheSecond, AdjustReason.NONE)[8]).isEqualTo(0.toByte())
    }

    @Test
    fun `the adjust reason is the last byte`() {
        val time = LocalDateTime.of(2026, 11, 16, 21, 58)

        assertThat(CurrentTimeService.currentTime(time, AdjustReason.MANUAL_TIME_UPDATE)[9]).isEqualTo(1.toByte())
        assertThat(CurrentTimeService.currentTime(time, AdjustReason.TIME_ZONE_CHANGE)[9]).isEqualTo(4.toByte())
    }

    @Test
    fun `India's +05 30 is 22 quarter hours, DST always 0`() {
        assertThat(CurrentTimeService.localTimeInformation(ZoneOffset.ofHoursMinutes(5, 30)).toString())
            .isEqualTo("16 00")
    }

    @Test
    fun `negative and extreme offsets are signed quarter hours`() {
        assertThat(CurrentTimeService.localTimeInformation(ZoneOffset.ofHoursMinutes(-3, -30)).toString())
            .isEqualTo("F2 00") // -14
        assertThat(CurrentTimeService.localTimeInformation(ZoneOffset.UTC).toString()).isEqualTo("00 00")
        assertThat(CurrentTimeService.localTimeInformation(ZoneOffset.ofHours(14)).toString()).isEqualTo("38 00")
    }
}

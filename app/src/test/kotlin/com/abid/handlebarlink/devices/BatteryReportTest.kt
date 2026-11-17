package com.abid.handlebarlink.devices

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class BatteryReportTest {
    @Test
    fun `battery level 0 to 9 reads as 10 to 100 percent`() {
        assertThat(batteryPercent(listOf(1, 1, 0))).isEqualTo(10)
        assertThat(batteryPercent(listOf(1, 1, 7))).isEqualTo(80)
        assertThat(batteryPercent(listOf(1, 1, 9))).isEqualTo(100)
    }

    @Test
    fun `the battery pair is found among other pairs (docking state first)`() {
        assertThat(batteryPercent(listOf(2, 2, 0, 1, 4))).isEqualTo(50)
    }

    @Test
    fun `no battery pair, a bad level or a short report gives nothing, never 0`() {
        assertThat(batteryPercent(listOf(1, 2, 0))).isNull()
        assertThat(batteryPercent(listOf(1, 1, 12))).isNull()
        assertThat(batteryPercent(listOf(2, 1))).isNull()
        assertThat(batteryPercent(listOf(1, 1, null))).isNull()
        assertThat(batteryPercent(emptyList())).isNull()
    }
}

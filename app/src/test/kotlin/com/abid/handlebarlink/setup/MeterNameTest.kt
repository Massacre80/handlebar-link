package com.abid.handlebarlink.setup

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

class MeterNameTest {
    @Test
    fun `the meter's name is cut before the bytes that are not text (capture 2026-11-17)`() {
        assertThat(cleanName("HONDA BTU 2040����")).isEqualTo("HONDA BTU 2040")
        assertThat(cleanName("HONDA BTU 2040\u0000\u0000")).isEqualTo("HONDA BTU 2040")
        assertThat(cleanName("HONDA BTU 2040\u00FF\u00FF\uD83D\uDE00")).isEqualTo("HONDA BTU 2040")
    }

    @Test
    fun `clean names stay as they are, and empty ones are dropped`() {
        assertThat(cleanName("HONDA BTU SIM")).isEqualTo("HONDA BTU SIM")
        assertThat(cleanName("�")).isNull()
        assertThat(cleanName("  ")).isNull()
        assertThat(cleanName(null)).isNull()
    }

    @Test
    fun `Honda meters are told apart by their name`() {
        assertThat(isHondaMeter("HONDA BTU 2040")).isTrue()
        assertThat(isHondaMeter("HONDA BTU ARD")).isTrue()
        assertThat(isHondaMeter("KD-43X7500F")).isFalse()
        assertThat(isHondaMeter("honda")).isFalse()
        assertThat(isHondaMeter("Meter", vehicleService = true)).isTrue()
    }
}

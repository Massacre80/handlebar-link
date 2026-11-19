package com.abid.handlebarlink.carplay.engine.android.pairing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

class IphoneEvidenceTest {
    @Test
    fun `the iAP2 record proves an iPhone whatever its name`() {
        assertThat(IphoneEvidence.isIphone("Abid's phone", setOf(HANDS_FREE, IAP2_RECORD))).isTrue()
        assertThat(IphoneEvidence.isIphone(null, setOf(IAP2_RECORD))).isTrue()
    }

    @Test
    fun `an iPhone name proves an iPhone without records, in any case and with NUL padding`() {
        assertThat(IphoneEvidence.isIphone("Abid's iPhone", emptySet())).isTrue()
        assertThat(IphoneEvidence.isIphone("IPHONE 15", emptySet())).isTrue()
        assertThat(IphoneEvidence.isIphone("iPhone\u0000\u0000", emptySet())).isTrue()
    }

    @Test
    fun `other phones, headsets and iPads are not iPhones`() {
        assertThat(IphoneEvidence.isIphone("Galaxy S24", setOf(HANDS_FREE))).isFalse()
        assertThat(IphoneEvidence.isIphone("EJEAS V6", setOf(AUDIO_SINK))).isFalse()
        assertThat(IphoneEvidence.isIphone("Abid's iPad", emptySet())).isFalse()
        assertThat(IphoneEvidence.isIphone(null, emptySet())).isFalse()
    }

    @Test
    fun `a name is shown without NULs and spaces, the address when nothing is left`() {
        assertThat(IphoneEvidence.display(" iPhone\u0000 ", "AA:BB")).isEqualTo("iPhone")
        assertThat(IphoneEvidence.display("\u0000\u0000", "AA:BB")).isEqualTo("AA:BB")
        assertThat(IphoneEvidence.clean("   ")).isNull()
    }

    @Test
    fun `a Bluetooth name may have up to 248 UTF-8 bytes and is never blank`() {
        assertThat(BluetoothName.isValid("CB350RS")).isTrue()
        assertThat(BluetoothName.isValid("a".repeat(248))).isTrue()
        assertThat(BluetoothName.isValid("a".repeat(249))).isFalse()
        // "é" is two bytes in UTF-8: 124 of them are 248 bytes, 125 are 250.
        assertThat(BluetoothName.isValid("é".repeat(124))).isTrue()
        assertThat(BluetoothName.isValid("é".repeat(125))).isFalse()
        assertThat(BluetoothName.isValid("  ")).isFalse()
    }
}

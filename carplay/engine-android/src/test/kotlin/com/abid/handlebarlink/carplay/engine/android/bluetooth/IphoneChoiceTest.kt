package com.abid.handlebarlink.carplay.engine.android.bluetooth

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import org.junit.Test

class IphoneChoiceTest {
    private val meter = BondedDevice("10:00:00:00:00:01", "HONDA BTU 2040")
    private val intercom = BondedDevice("10:00:00:00:00:02", "EJEAS V6")
    private val abid = BondedDevice("20:00:00:00:00:01", "Abid's iPhone")
    private val spare = BondedDevice("20:00:00:00:00:02", "iPhone SE")

    private fun failure(
        chosen: String?,
        bonded: List<BondedDevice>,
        connected: Set<String> = emptySet(),
    ) = assertFailure { IphoneChoice.choose(chosen, bonded, connected) }
        .isInstanceOf<CarPlayException>()
        .prop(CarPlayException::error)
        .isInstanceOf<CarPlayError.BluetoothUnavailable>()

    @Test
    fun `the only bonded iPhone is chosen among the bike's other devices`() {
        assertThat(IphoneChoice.choose(null, listOf(meter, abid, intercom), emptySet())).isEqualTo(abid)
    }

    @Test
    fun `the rider's choice wins, matched without case`() {
        assertThat(IphoneChoice.choose("20:00:00:00:00:02".lowercase(), listOf(abid, spare), setOf(abid.address)))
            .isEqualTo(spare)
    }

    @Test
    fun `a chosen iPhone that is no longer bonded is an error, never another phone`() {
        failure("20:00:00:00:00:09", listOf(abid))
    }

    @Test
    fun `with two iPhones the connected one is chosen`() {
        assertThat(
            IphoneChoice.choose(null, listOf(abid, spare), setOf("20:00:00:00:00:02".lowercase())),
        ).isEqualTo(spare)
    }

    @Test
    fun `two iPhones and none or both connected is ambiguous`() {
        failure(null, listOf(abid, spare))
        failure(null, listOf(abid, spare), setOf(abid.address, spare.address))
    }

    @Test
    fun `no iPhone is an error even when a single other device is bonded (unlike DiPlay)`() {
        failure(null, listOf(meter))
        failure(null, emptyList())
    }
}

package com.abid.handlebarlink.carplay.engine.android.bluetooth

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.abid.handlebarlink.carplay.engine.CarPlayException
import org.junit.Test

/** An iPhone forgotten in the app stays paired with Android but is never dialled by itself. */
class ForgottenIphoneTest {
    private val abid = BondedDevice("20:00:00:00:00:01", "Abid's iPhone")
    private val spare = BondedDevice("20:00:00:00:00:02", "iPhone SE")

    @Test
    fun `a forgotten iPhone is left out of the automatic choice, matched without case`() {
        val forgot = setOf("20:00:00:00:00:01")
        assertThat(IphoneChoice.choose(null, listOf(abid, spare), emptySet(), forgot)).isEqualTo(spare)
        assertThat(
            IphoneChoice.choose(null, listOf(abid.copy(address = abid.address.lowercase()), spare), emptySet(), forgot),
        ).isEqualTo(spare)
    }

    @Test
    fun `the only paired iPhone, once forgotten, is no iPhone at all`() {
        assertFailure { IphoneChoice.choose(null, listOf(abid), setOf(abid.address), setOf(abid.address)) }
            .isInstanceOf<CarPlayException>()
    }
}

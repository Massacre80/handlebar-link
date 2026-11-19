package com.abid.handlebarlink.carplay

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadset
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.devices.PHONE
import com.abid.handlebarlink.devices.SavedDevice
import org.junit.Test

/** Which headset our CarPlay's calls and Siri prefer, from the rider's devices and call choice. */
class RiderHeadsetsTest {
    private val earbuds = SavedDevice("AA:00:00:00:00:02", "Buds", DeviceKind.HEADSET, "Pixel Buds")
    private val intercom = SavedDevice("AA:00:00:00:00:01", "Helmet", DeviceKind.INTERCOM, "EJEAS V6 Pro+")

    @Test
    fun `calls on the phone leave CarPlay on the phone`() {
        assertThat(carPlayHeadset(listOf(intercom), null, PHONE)).isNull()
    }

    @Test
    fun `the chosen device wins, else the primary one, which is the intercom by default`() {
        val devices = listOf(earbuds, intercom)
        assertThat(carPlayHeadset(devices, null, earbuds.address))
            .isEqualTo(CarPlayHeadset(earbuds.address, "Pixel Buds"))
        assertThat(carPlayHeadset(devices, null, null)).isEqualTo(CarPlayHeadset(intercom.address, "EJEAS V6 Pro+"))
        assertThat(carPlayHeadset(devices, earbuds.address, null))
            .isEqualTo(CarPlayHeadset(earbuds.address, "Pixel Buds"))
    }

    @Test
    fun `with no device added any connected headset is used`() {
        assertThat(carPlayHeadset(emptyList(), null, null)).isEqualTo(CarPlayHeadset())
    }
}

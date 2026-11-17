package com.abid.handlebarlink.ui.devices

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.devices.Discovery
import com.abid.handlebarlink.devices.NearbyDevice
import org.junit.Test

class PairDeviceStateTest {
    private val intercom = NearbyDevice("AA", "EJEAS V6 Pro+", audio = true, suggested = DeviceKind.INTERCOM)
    private val buds = NearbyDevice("BB", "buds", audio = true, suggested = DeviceKind.HEADSET)
    private val tv = NearbyDevice("CC", "TV", audio = false, suggested = DeviceKind.OTHER)
    private val meter = NearbyDevice("DD", "HONDA BTU 2040", audio = false, suggested = DeviceKind.OTHER)

    @Test
    fun `audio first by name, others apart, the bike's meter never offered`() {
        val state = pairDeviceState(Discovery.Found(listOf(tv, meter, intercom, buds), searching = true), null)
        assertThat(state).isInstanceOf(PairDeviceUiState.Nearby::class)
        state as PairDeviceUiState.Nearby
        assertThat(state.audio.map { it.address }).containsExactly("BB", "AA")
        assertThat(state.other.map { it.address }).containsExactly("CC")
        assertThat(state.searching).isEqualTo(true)
    }

    @Test
    fun `no permission and Bluetooth off have their own states`() {
        assertThat(pairDeviceState(Discovery.NotAllowed, null)).isEqualTo(PairDeviceUiState.NotAllowed)
        assertThat(pairDeviceState(Discovery.Off, null)).isEqualTo(PairDeviceUiState.BluetoothOff)
    }
}

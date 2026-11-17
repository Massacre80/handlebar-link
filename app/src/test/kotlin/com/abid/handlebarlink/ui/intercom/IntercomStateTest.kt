package com.abid.handlebarlink.ui.intercom

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.devices.DeviceStatus
import com.abid.handlebarlink.devices.MediaKeys
import com.abid.handlebarlink.devices.MediaRoute
import com.abid.handlebarlink.devices.SavedDevice
import org.junit.Test

class IntercomStateTest {
    private val buds = SavedDevice("BB", "Buds", DeviceKind.HEADSET, "Galaxy Buds2")
    private val mine = SavedDevice("AA", "Mine", DeviceKind.INTERCOM, "V6 Pro+")
    private val passenger = SavedDevice("CC", "Passenger", DeviceKind.INTERCOM, "V6 Pro+")
    private val saved = listOf(buds, mine, passenger)

    private fun state(
        chosen: String? = null,
        calls: String? = null,
        route: MediaRoute = MediaRoute.Unknown,
        keys: MediaKeys = MediaKeys.Unknown,
        statuses: Map<String, DeviceStatus> = emptyMap(),
    ) = intercomState(saved, Choices(chosen, calls), statuses, AudioNow(route, keys), vendorApp = false)

    @Test
    fun `the first intercom is primary until the rider chooses another, which then comes first`() {
        assertThat(state().devices.map { it.address }).containsExactly("AA", "BB", "CC")
        assertThat(state(chosen = "CC").devices.map { it.address }).containsExactly("CC", "BB", "AA")
        // A chosen device that was removed no longer counts.
        assertThat(state(chosen = "ZZ").primary?.address).isEqualTo("AA")
    }

    @Test
    fun `music says where it plays, the primary, another added device by name, another device, the phone`() {
        assertThat(state(route = MediaRoute.Bluetooth("AA")).music).isEqualTo(Music.Primary)
        assertThat(state(route = MediaRoute.Bluetooth("BB")).music).isEqualTo(Music.Other("Buds"))
        assertThat(state(route = MediaRoute.Bluetooth("XX")).music).isEqualTo(Music.OtherBluetooth)
        assertThat(state(route = MediaRoute.Phone).music).isEqualTo(Music.Phone)
        assertThat(state().music).isEqualTo(Music.Unknown)
    }

    @Test
    fun `buttons reach CarPlay only when CarLink holds the media keys`() {
        assertThat(state(keys = MediaKeys.App("com.carlink.app")).buttons).isEqualTo(Buttons.CARLINK)
        assertThat(state(keys = MediaKeys.App("com.spotify.music")).buttons).isEqualTo(Buttons.OTHER_APP)
        assertThat(state().buttons).isEqualTo(Buttons.UNKNOWN)
    }

    @Test
    fun `live state per device, nothing assumed for a device Android does not report`() {
        val live = DeviceStatus(connected = true, battery = 60, calls = true, music = false)
        val primary = state(statuses = mapOf("AA" to live)).primary!!
        assertThat(primary.calls).isEqualTo(true)
        assertThat(primary.music).isEqualTo(false)
        assertThat(primary.battery).isEqualTo(60)
        assertThat(state().primary!!.battery).isNull()
    }

    @Test
    fun `nothing added, no primary`() {
        val empty =
            intercomState(emptyList(), Choices(), emptyMap(), AudioNow(MediaRoute.Phone, MediaKeys.Unknown), false)
        assertThat(empty.primary).isNull()
    }

    @Test
    fun `calls go where the rider chose, else to the primary, never to a removed device`() {
        assertThat(state().calls).isEqualTo(Calls.Device("AA", "Mine"))
        assertThat(state(calls = "CC").calls).isEqualTo(Calls.Device("CC", "Passenger"))
        assertThat(state(calls = "phone").calls).isEqualTo(Calls.Phone)
        assertThat(state(calls = "ZZ").calls).isEqualTo(Calls.Device("AA", "Mine"))
    }
}

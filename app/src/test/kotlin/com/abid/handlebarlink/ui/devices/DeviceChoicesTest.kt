package com.abid.handlebarlink.ui.devices

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.devices.DeviceStatus
import com.abid.handlebarlink.devices.PairedDevice
import com.abid.handlebarlink.devices.SavedDevice
import org.junit.Test

class DeviceChoicesTest {
    private val intercom = PairedDevice("AA", "EJEAS V6 Pro+", audio = true, suggested = DeviceKind.INTERCOM)
    private val buds = PairedDevice("BB", "Buds", audio = true, suggested = DeviceKind.HEADSET)
    private val watch = PairedDevice("CC", "Watch", audio = false, suggested = DeviceKind.OTHER)
    private val meter = PairedDevice("DD", "HONDA BTU 2040", audio = false, suggested = DeviceKind.OTHER)
    private val nameless = PairedDevice("EE", null, audio = false, suggested = DeviceKind.OTHER)

    @Test
    fun `audio first with the connected on top, others apart, the bike's meter left out`() {
        val ready =
            deviceChoices(
                listOf(watch, meter, buds, intercom, nameless),
                statuses = mapOf("BB" to DeviceStatus(connected = true)),
                saved = emptyList(),
                bikeAddress = null,
            )
        assertThat(ready.audio.map { it.name }).containsExactly("Buds", "EJEAS V6 Pro+")
        assertThat(ready.other.map { it.name }).containsExactly("EE", "Watch")
    }

    @Test
    fun `the selected bike is left out even under another name`() {
        val bike = PairedDevice("FF", "My bike", audio = false, suggested = DeviceKind.OTHER)
        val ready = deviceChoices(listOf(bike, watch), emptyMap(), emptyList(), bikeAddress = "FF")
        assertThat(ready.other.map { it.address }).containsExactly("CC")
    }

    @Test
    fun `an added device shows the rider's name and kind`() {
        val ready =
            deviceChoices(
                listOf(intercom),
                emptyMap(),
                saved = listOf(SavedDevice("AA", "Helmet", DeviceKind.HEADSET, "EJEAS V6 Pro+")),
                bikeAddress = null,
            )
        val choice = ready.audio.single()
        assertThat(choice.name).isEqualTo("Helmet")
        assertThat(choice.model).isEqualTo("EJEAS V6 Pro+")
        assertThat(choice.kind).isEqualTo(DeviceKind.HEADSET)
        assertThat(choice.added).isEqualTo(true)
    }

    @Test
    fun `a live audio connection makes a device audio whatever its class says`() {
        val ready = deviceChoices(listOf(watch), mapOf("CC" to DeviceStatus(connected = true)), emptyList(), null)
        assertThat(ready.audio.map { it.address }).containsExactly("CC")
    }
}

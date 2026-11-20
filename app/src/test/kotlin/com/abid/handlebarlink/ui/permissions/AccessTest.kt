package com.abid.handlebarlink.ui.permissions

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/**
 * Where a list of runtime permissions stands ([accessOf]): refused for good only when Android would show no dialog
 * for any missing permission, decided per permission (android-permissions-security §6).
 */
class AccessTest {
    private val bluetooth = "android.permission.BLUETOOTH_CONNECT"
    private val wifi = "android.permission.NEARBY_WIFI_DEVICES"
    private val microphone = "android.permission.RECORD_AUDIO"

    private fun access(
        missing: List<String>,
        asked: Set<String>,
        rationale: Set<String> = emptySet(),
    ) = accessOf(missing, { it in asked }, { it in rationale })

    @Test
    fun `nothing missing is granted, whatever was asked`() {
        assertThat(access(emptyList(), asked = setOf(bluetooth))).isEqualTo(Access.GRANTED)
    }

    @Test
    fun `a list one of whose permissions was asked elsewhere still asks for the others`() {
        // The pairing asked for Bluetooth (granted); CarPlay's list still misses the microphone and Nearby Wi-Fi,
        // never asked, so Android still shows its dialog for them.
        assertThat(access(listOf(wifi, microphone), asked = setOf(bluetooth))).isEqualTo(Access.ASK)
        // One missing permission refused for good, another never asked: asking still shows the other's dialog.
        assertThat(access(listOf(wifi, microphone), asked = setOf(bluetooth, microphone))).isEqualTo(Access.ASK)
    }

    @Test
    fun `refused for good once every missing permission was asked and has no rationale`() {
        assertThat(access(listOf(wifi, microphone), asked = setOf(wifi, microphone))).isEqualTo(Access.BLOCKED)
        // Asked on another screen counts as asked here (the shared set): Allow becomes Open settings at once.
        assertThat(access(listOf(microphone), asked = setOf(microphone))).isEqualTo(Access.BLOCKED)
    }

    @Test
    fun `a rationale means Android still asks, and never asked is never refused for good`() {
        assertThat(access(listOf(microphone), asked = setOf(microphone), rationale = setOf(microphone)))
            .isEqualTo(Access.ASK)
        assertThat(access(listOf(microphone), asked = emptySet())).isEqualTo(Access.ASK)
        // Without an activity no rationale can be read: never refused for good.
        assertThat(accessOf(listOf(microphone), { true }, null)).isEqualTo(Access.ASK)
    }
}

package com.abid.handlebarlink.carplay.engine.session

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/**
 * The one address StartSession and Bonjour publish, as DiPlay's `HotspotAddressPolicy.select` (HotspotAddressPolicy.kt:
 * 19-30): the first usable IPv4, else the first link-local IPv6, written without its scope (CarPlayController.kt:
 * 3404-3410).
 */
class PublishedAddressTest {
    @Test
    fun `the group's IPv4 is published even when the platform lists IPv6 first`() {
        assertThat(WirelessAttempt.published(listOf("fe80::1%p2p-wlan0-0", "fd00::5", "192.168.49.1")))
            .isEqualTo("192.168.49.1")
    }

    @Test
    fun `without IPv4 the link-local address is published, without its scope`() {
        assertThat(WirelessAttempt.published(listOf("fd00::5", "fe80::1%p2p-wlan0-0"))).isEqualTo("fe80::1")
    }

    @Test
    fun `with neither, the first address is published`() {
        assertThat(WirelessAttempt.published(listOf("fd00::5", "fd00::6"))).isEqualTo("fd00::5")
    }
}

package com.abid.handlebarlink.carplay.engine.android.wifi

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.android.MapSettings
import org.junit.Test
import java.security.SecureRandom

class StableNetworksTest {
    private val settings = MapSettings()

    private fun networks() = StableNetworks(settings, SecureRandom())

    @Test
    fun `an iPhone gets the same name and passphrase every time, also after a restart`() {
        val first = networks().networkFor("AA:BB:CC:DD:EE:01")
        val again = networks().networkFor("AA:BB:CC:DD:EE:01")
        val lowerCase = networks().networkFor("aa:bb:cc:dd:ee:01")
        assertThat(again.ssid).isEqualTo(first.ssid)
        assertThat(again.passphrase).isEqualTo(first.passphrase)
        assertThat(lowerCase.passphrase).isEqualTo(first.passphrase)
    }

    @Test
    fun `two iPhones get different networks`() {
        val one = networks().networkFor("AA:BB:CC:DD:EE:01")
        val two = networks().networkFor("AA:BB:CC:DD:EE:02")
        assertThat(two.passphrase).isNotEqualTo(one.passphrase)
    }

    @Test
    fun `the name is a valid Wi-Fi Direct name and recognised as ours`() {
        val network = networks().networkFor(StableNetworks.ANY)
        // WifiP2pConfig.Builder.setNetworkName: "DIRECT-xy" prefix, at most 32 bytes.
        assertThat(Regex("^DIRECT-[A-Za-z0-9]{2}.*").matches(network.ssid)).isTrue()
        assertThat(network.ssid.toByteArray().size).isLessThanOrEqualTo(32)
        assertThat(StableNetworks.isOurs(network.ssid)).isTrue()
        assertThat(StableNetworks.isOurs("DIRECT-ab-CarLink")).isFalse()
        assertThat(StableNetworks.isValidPassphrase(network.passphrase)).isTrue()
        assertThat(network.toString().contains(network.passphrase)).isFalse()
    }

    @Test
    fun `a broken stored network is replaced and the replacement stored`() {
        settings.values["wifi.network.AA:BB:CC:DD:EE:01"] = "DIRECT-ab-HandlebarLink\nshort"
        val replaced = networks().networkFor("AA:BB:CC:DD:EE:01")
        assertThat(StableNetworks.isValidPassphrase(replaced.passphrase)).isTrue()
        assertThat(networks().networkFor("AA:BB:CC:DD:EE:01").passphrase).isEqualTo(replaced.passphrase)
    }

    @Test
    fun `the working channel is remembered per iPhone`() {
        networks().rememberFrequency("AA:BB:CC:DD:EE:01", 5745)
        assertThat(networks().frequency("aa:bb:cc:dd:ee:01")).isEqualTo(5745)
        assertThat(networks().frequency("AA:BB:CC:DD:EE:02")).isNull()
    }
}

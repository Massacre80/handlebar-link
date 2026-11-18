package com.abid.handlebarlink.carplay.engine.android.wifi

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import java.net.InetAddress

class GroupFactsTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text) // numeric literals: no lookup

    /** The platform's own text for an IPv6 address (the JVM writes it in full, Android compressed), unscoped. */
    private fun text(address: String): String = ip(address).hostAddress!!.substringBefore('%')

    @Test
    fun `IPv4 goes first and a link-local IPv6 address carries the interface as its scope`() {
        val addresses = listOf(ip("fe80::1"), ip("192.168.49.1"), ip("fd00::5"))
        assertThat(GroupFacts.addresses("p2p-wlan0-0", addresses))
            .containsExactly("192.168.49.1", text("fe80::1") + "%p2p-wlan0-0", text("fd00::5"))
    }

    @Test
    fun `a numeric scope from the platform is replaced by the interface name`() {
        val scoped = ip("fe80::1%1")
        assertThat(
            GroupFacts.addresses("p2p-wlan0-3", listOf(scoped)),
        ).containsExactly(text("fe80::1") + "%p2p-wlan0-3")
    }

    @Test
    fun `loopback and wildcard addresses are left out`() {
        val addresses = listOf(ip("127.0.0.1"), ip("::1"), ip("0.0.0.0"), ip("192.168.49.1"), ip("192.168.49.1"))
        assertThat(GroupFacts.addresses("p2p-wlan0-0", addresses)).containsExactly("192.168.49.1")
    }

    @Test
    fun `frequencies map to channel numbers`() {
        assertThat(GroupFacts.channel(2412)).isEqualTo(1)
        assertThat(GroupFacts.channel(2437)).isEqualTo(6)
        assertThat(GroupFacts.channel(2462)).isEqualTo(11)
        assertThat(GroupFacts.channel(2484)).isEqualTo(14)
        assertThat(GroupFacts.channel(5180)).isEqualTo(36)
        assertThat(GroupFacts.channel(5745)).isEqualTo(149)
        assertThat(GroupFacts.channel(5825)).isEqualTo(165)
        assertThat(GroupFacts.channel(60480)).isNull()
    }
}

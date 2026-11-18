package com.abid.handlebarlink.carplay.engine.android.net

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.Test
import java.io.IOException
import java.net.BindException

class PortBinderTest {
    /** Binds address:port unless that pair is taken; records what is open. */
    private class Binder(
        private val taken: Set<Pair<String, Int>> = emptySet(),
        private val ephemeral: Int = 40_000,
    ) {
        val open = mutableListOf<Pair<String, Int>>()

        fun bind(
            address: String,
            port: Int,
        ): Pair<String, Int> {
            val actual = if (port == 0) ephemeral else port
            if (address to actual in taken) throw BindException("in use: $address:$actual")
            return (address to actual).also { open += it }
        }

        fun close(server: Pair<String, Int>) {
            open -= server
        }
    }

    private fun bindAll(
        binder: Binder,
        addresses: List<String>,
    ) = PortBinder.bindAll(addresses, 7000, binder::bind, { it.second }, binder::close)

    @Test
    fun `the preferred port is bound on every address`() {
        val binder = Binder()
        assertThat(bindAll(binder, listOf("192.168.49.1", "fe80::1%p2p0")))
            .containsExactly("192.168.49.1" to 7000, "fe80::1%p2p0" to 7000)
    }

    @Test
    fun `a port busy on one address is given up on all of them`() {
        val binder = Binder(taken = setOf("fe80::1%p2p0" to 7000, "192.168.49.1" to 7001))
        assertThat(bindAll(binder, listOf("192.168.49.1", "fe80::1%p2p0")))
            .containsExactly("192.168.49.1" to 7002, "fe80::1%p2p0" to 7002)
        assertThat(binder.open).containsExactly("192.168.49.1" to 7002, "fe80::1%p2p0" to 7002)
    }

    @Test
    fun `after 7000 to 7010 an ephemeral port is used, the same on every address`() {
        val taken = (7000..7010).map { "192.168.49.1" to it }.toSet()
        val binder = Binder(taken = taken)
        assertThat(bindAll(binder, listOf("192.168.49.1", "fe80::1%p2p0")))
            .containsExactly("192.168.49.1" to 40_000, "fe80::1%p2p0" to 40_000)
    }

    @Test
    fun `the candidates are DiPlay's`() {
        assertThat(PortBinder.candidates(7000)).isEqualTo(listOf(7000) + (7001..7010).toList() + List(4) { 0 })
    }

    @Test
    fun `any other failure is thrown at once and closes what was bound`() {
        val binder = Binder()
        assertFailure {
            PortBinder.bindAll(
                listOf("192.168.49.1", "bad"),
                7000,
                { address, port ->
                    if (address ==
                        "bad"
                    ) {
                        throw IOException("no such address")
                    } else {
                        binder.bind(address, port)
                    }
                },
                { it.second },
                binder::close,
            )
        }.isInstanceOf<IOException>()
        assertThat(binder.open).isEmpty()
    }
}

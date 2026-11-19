package com.abid.handlebarlink.carplay.engine.android.net

import java.io.IOException
import java.net.BindException

/**
 * Binds one port on every address, DiPlay's `AirPlayPortSelector.bindAll` (AirPlayPortSelector.kt:20-49): the
 * preferred port, then the next ten, then a few ephemeral tries where the first address picks the port and the rest
 * follow it. A port busy on any address is given up on all of them (the bound ones are closed) and the next is
 * tried; any other failure is thrown at once.
 */
internal object PortBinder {
    /** The ports tried, in order (0 = ephemeral). A [preferredPort] of 0 asks for an ephemeral port only. */
    fun candidates(preferredPort: Int): List<Int> =
        if (preferredPort == 0) {
            List(EPHEMERAL_TRIES) { 0 }
        } else {
            listOf(preferredPort) + (preferredPort + 1..preferredPort + FALLBACK_PORTS).toList() +
                List(EPHEMERAL_TRIES) { 0 }
        }

    fun <A, S> bindAll(
        addresses: List<A>,
        preferredPort: Int,
        bind: (A, Int) -> S,
        portOf: (S) -> Int,
        close: (S) -> Unit,
    ): List<S> {
        require(addresses.isNotEmpty()) { "At least one address is required" }
        val busy = mutableListOf<BindException>()
        for (candidate in candidates(preferredPort)) {
            val bound = mutableListOf<S>()
            try {
                for (address in addresses.distinct()) {
                    bound += bind(address, bound.firstOrNull()?.let(portOf) ?: candidate)
                }
                return bound
            } catch (e: BindException) {
                bound.forEach { closeQuietly(it, close) }
                busy += e
            } catch (e: IOException) {
                bound.forEach { closeQuietly(it, close) }
                throw e
            }
        }
        throw BindException(
            "No common port free on $addresses from $preferredPort",
        ).apply { busy.forEach(::addSuppressed) }
    }

    private fun <S> closeQuietly(
        server: S,
        close: (S) -> Unit,
    ) {
        try {
            close(server)
        } catch (_: IOException) {
            // Closing a listener that failed alongside; nothing more to do.
        }
    }

    /** DiPlay's FALLBACK_PORTS 7001..7010 after 7000 (AirPlayPortSelector.kt:17). */
    private const val FALLBACK_PORTS = 10

    /** DiPlay's `List(4) { 0 }` (AirPlayPortSelector.kt:27). */
    private const val EPHEMERAL_TRIES = 4
}

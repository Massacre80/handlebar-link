package com.abid.handlebarlink.core.log

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LoggerTest {
    private val at = Instant.parse("2026-11-16T15:31:03.412Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    @Test
    fun `every sink receives the same entry`() {
        val first = mutableListOf<LogEntry>()
        val second = mutableListOf<LogEntry>()
        val logger = Logger("bike", clock, listOf(LogSink(first::add), LogSink(second::add)))

        logger.info("link.session.ready", "attempt" to 2)

        assertThat(first).hasSize(1)
        assertThat(second.single()).isSameInstanceAs(first.single())
        assertThat(first.single()).isEqualTo(
            LogEntry(at, Level.INFO, "bike", "link.session.ready", listOf("attempt" to 2), null),
        )
    }

    @Test
    fun `each helper logs at its level and child keeps the sinks`() {
        val entries = mutableListOf<LogEntry>()
        val logger = Logger("bike", clock, listOf(LogSink(entries::add)))
        val error = IllegalStateException("boom")

        logger.debug("a")
        logger.warn("b")
        logger.error("c", error)
        logger.child("gatt").info("d")

        assertThat(entries.map { it.level }).containsExactly(Level.DEBUG, Level.WARN, Level.ERROR, Level.INFO)
        assertThat(entries[2].error).isSameInstanceAs(error)
        assertThat(entries[3].tag).isEqualTo("gatt")
    }
}

package com.abid.handlebarlink.core.log

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class LogfmtTest {
    private val logfmt = Logfmt(ZoneOffset.ofHoursMinutes(5, 30))
    private val at = Instant.parse("2026-11-16T15:31:03.412Z")

    @Test
    fun `renders the documented line shape in local time`() {
        val entry =
            LogEntry(
                at,
                Level.INFO,
                "bike",
                "link.session.ready",
                listOf("attempt" to 2, "series" to "03F5"),
                null,
            )

        assertThat(logfmt.render(entry))
            .isEqualTo("2026-11-16T21:01:03.412+05:30 I bike link.session.ready attempt=2 series=03F5")
    }

    @Test
    fun `values with spaces, quotes or equals signs are quoted and escaped`() {
        val entry =
            LogEntry(at, Level.WARN, "bike", "e", listOf("name" to "HONDA BTU 2040", "q" to "a\"b", "x" to "k=v"), null)

        assertThat(logfmt.render(entry))
            .isEqualTo("2026-11-16T21:01:03.412+05:30 W bike e name=\"HONDA BTU 2040\" q=\"a\\\"b\" x=\"k=v\"")
    }

    @Test
    fun `null and empty values and errors are rendered`() {
        val entry =
            LogEntry(at, Level.ERROR, "bike", "e", listOf("a" to null, "b" to ""), IllegalStateException("bad state"))

        assertThat(logfmt.render(entry))
            .isEqualTo(
                "2026-11-16T21:01:03.412+05:30 E bike e a=null b=\"\" error=\"IllegalStateException: bad state\"",
            )
    }
}

package com.abid.handlebarlink.diagnostics

import assertk.assertThat
import assertk.assertions.containsExactly
import com.abid.handlebarlink.core.common.log.Level
import com.abid.handlebarlink.core.common.log.LogEntry
import org.junit.Test
import java.time.Instant

class RingBufferSinkTest {
    private fun entry(event: String) = LogEntry(Instant.EPOCH, Level.INFO, "t", event, emptyList(), null)

    @Test
    fun `keeps the newest entries, oldest first`() {
        val sink = RingBufferSink(capacity = 3)

        listOf("a", "b", "c", "d", "e").forEach { sink.write(entry(it)) }

        assertThat(sink.entries.value.map { it.event }).containsExactly("c", "d", "e")
    }
}

package com.abid.handlebarlink.core.testing

import com.abid.handlebarlink.core.common.log.LogEntry
import com.abid.handlebarlink.core.common.log.LogSink
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider

/** Keeps every log entry so tests can assert what was logged. */
public class RecordingLogSink : LogSink {
    private val recorded = mutableListOf<LogEntry>()

    /** Everything logged so far, in order. */
    public val entries: List<LogEntry> get() = recorded.toList()

    /** The entries with this event name. */
    public fun events(event: String): List<LogEntry> = recorded.filter { it.event == event }

    override fun write(entry: LogEntry) {
        recorded += entry
    }
}

/** A logger that writes to [sink], timestamped by [time]. */
public fun testLogger(
    sink: RecordingLogSink,
    time: TimeProvider,
    tag: String = "test",
): Logger = Logger(tag, time.clock, listOf(sink))

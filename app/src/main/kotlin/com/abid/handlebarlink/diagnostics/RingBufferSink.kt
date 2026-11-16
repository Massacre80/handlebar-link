package com.abid.handlebarlink.diagnostics

import com.abid.handlebarlink.core.common.log.LogEntry
import com.abid.handlebarlink.core.common.log.LogSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The last [capacity] log entries, newest last, for the in-app log view. Non-blocking. */
class RingBufferSink(
    private val capacity: Int = DEFAULT_CAPACITY,
) : LogSink {
    private val buffer = MutableStateFlow<List<LogEntry>>(emptyList())

    /** The retained entries, oldest first. */
    val entries: StateFlow<List<LogEntry>> = buffer.asStateFlow()

    override fun write(entry: LogEntry) {
        buffer.update { (it + entry).takeLast(capacity) }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 500
    }
}

package com.abid.handlebarlink.core.common.log

import java.time.Instant

/** Severity of a [LogEntry]. */
public enum class Level(
    /** One-letter code used in rendered lines. */
    public val letter: Char,
) {
    /** Details for debugging. */
    DEBUG('D'),

    /** Normal events worth keeping in a ride log. */
    INFO('I'),

    /** Something went wrong but the app recovers by itself. */
    WARN('W'),

    /** A failure, or a bug in our code. */
    ERROR('E'),
}

/**
 * One structured log event. [event] is a stable, dotted, lower-case name (`link.session.ready`);
 * data goes in [fields], never in free text (docs/research/01-architecture.md §3.2).
 */
public data class LogEntry(
    /** When it happened. */
    val at: Instant,
    /** Severity. */
    val level: Level,
    /** The component that logged it, e.g. `bike`. */
    val tag: String,
    /** Stable event name. */
    val event: String,
    /** Key/value data, in the order given. */
    val fields: List<Pair<String, Any?>>,
    /** The exception, if any. */
    val error: Throwable?,
)

/**
 * Where log entries go (logcat, the in-app ring buffer, a file). Loggers call sinks on the caller's
 * thread, which may be the main thread: a sink must not throw and must not block (hand disk I/O to its
 * own writer thread).
 */
public fun interface LogSink {
    /** Writes one entry. */
    public fun write(entry: LogEntry)
}

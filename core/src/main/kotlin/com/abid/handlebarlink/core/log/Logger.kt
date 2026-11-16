package com.abid.handlebarlink.core.log

import java.time.Clock

/**
 * Structured logger shared by every engine. Each call builds one [LogEntry] and hands it to every
 * sink. Timestamps come from the injected [clock], so tests control time.
 */
public class Logger(
    private val tag: String,
    private val clock: Clock,
    private val sinks: List<LogSink>,
) {
    /** Logs [event] at [level] with optional [error] and [fields]. */
    public fun log(
        level: Level,
        event: String,
        error: Throwable? = null,
        vararg fields: Pair<String, Any?>,
    ) {
        val entry = LogEntry(clock.instant(), level, tag, event, fields.toList(), error)
        sinks.forEach { it.write(entry) }
    }

    /** A logger with the same clock and sinks and another [tag]. */
    public fun child(tag: String): Logger = Logger(tag, clock, sinks)

    /** Logs at [Level.DEBUG]. */
    public fun debug(
        event: String,
        vararg fields: Pair<String, Any?>,
    ): Unit = log(Level.DEBUG, event, null, *fields)

    /** Logs at [Level.INFO]. */
    public fun info(
        event: String,
        vararg fields: Pair<String, Any?>,
    ): Unit = log(Level.INFO, event, null, *fields)

    /** Logs at [Level.WARN]. */
    public fun warn(
        event: String,
        vararg fields: Pair<String, Any?>,
    ): Unit = log(Level.WARN, event, null, *fields)

    /** Logs at [Level.ERROR], with the [error] that caused it. */
    public fun error(
        event: String,
        error: Throwable?,
        vararg fields: Pair<String, Any?>,
    ): Unit = log(Level.ERROR, event, error, *fields)
}

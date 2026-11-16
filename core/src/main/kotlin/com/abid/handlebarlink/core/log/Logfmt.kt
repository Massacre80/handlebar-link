package com.abid.handlebarlink.core.log

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders entries as one logfmt line each:
 * `2026-11-16T21:01:03.412+05:30 I bike link.session.ready attempt=2 series=03F5`.
 */
public class Logfmt(
    zone: ZoneId,
) {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(zone)

    /** The line for [entry], without a trailing newline. */
    public fun render(entry: LogEntry): String =
        buildString {
            append(time.format(entry.at)).append(' ')
            append(entry.level.letter).append(' ')
            append(entry.tag).append(' ')
            append(entry.event)
            entry.fields.forEach { (key, value) -> append(' ').append(key).append('=').append(quote(value)) }
            entry.error?.let { append(" error=").append(quote("${it::class.java.simpleName}: ${it.message}")) }
        }

    private fun quote(value: Any?): String {
        val text = value.toString()
        val plain = text.isNotEmpty() && text.none { it.isWhitespace() || it == '"' || it == '=' }
        return if (plain) text else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

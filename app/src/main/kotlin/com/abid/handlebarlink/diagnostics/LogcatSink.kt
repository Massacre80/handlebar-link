package com.abid.handlebarlink.diagnostics

import android.util.Log
import com.abid.handlebarlink.core.common.log.Level
import com.abid.handlebarlink.core.common.log.LogEntry
import com.abid.handlebarlink.core.common.log.LogSink
import com.abid.handlebarlink.core.common.log.Logfmt

/**
 * The one bridge from our [com.abid.handlebarlink.core.common.log.Logger] to Android's log
 * (checkForbiddenCode allows `android.util.Log` only here). One logfmt line per entry, tag `HandlebarLink`.
 */
class LogcatSink(
    private val minimum: Level,
    private val logfmt: Logfmt,
) : LogSink {
    override fun write(entry: LogEntry) {
        if (entry.level < minimum) return
        Log.println(priority(entry.level), TAG, logfmt.render(entry))
        entry.error?.let { Log.println(priority(entry.level), TAG, Log.getStackTraceString(it)) }
    }

    private fun priority(level: Level): Int =
        when (level) {
            Level.DEBUG -> Log.DEBUG
            Level.INFO -> Log.INFO
            Level.WARN -> Log.WARN
            Level.ERROR -> Log.ERROR
        }

    private companion object {
        const val TAG = "HandlebarLink"
    }
}

package com.abid.handlebarlink.carplay.engine.android

import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.test.TestCoroutineScheduler

/** A logger for tests that do not look at the log. */
internal fun quietLogger(): Logger = testLogger(RecordingLogSink(), TestTimeProvider(TestCoroutineScheduler()))

/** [Settings] in memory. */
internal class MapSettings : Settings {
    val values = HashMap<String, String>()

    override fun string(key: String): String? = values[key]

    override fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }
}

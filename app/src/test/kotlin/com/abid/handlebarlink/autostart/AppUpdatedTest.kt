package com.abid.handlebarlink.autostart

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.data.SelectedBike
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppUpdatedTest {
    private val settings = InMemoryBikeSettings()
    private val starts = mutableListOf<Boolean>()

    private fun TestScope.appUpdated() =
        AppUpdated(
            settings = settings,
            rides = {
                starts += it
                true
            },
            logger = testLogger(RecordingLogSink(), TestTimeProvider(testScheduler)),
        )

    @Test
    fun `with a bike the ride starts again, without a presence hint`() =
        runTest {
            settings.bike.value = SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040")

            appUpdated().resume()

            assertThat(starts).containsExactly(false)
        }

    @Test
    fun `without a bike nothing starts`() =
        runTest {
            appUpdated().resume()

            assertThat(starts).isEmpty()
        }
}

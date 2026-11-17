// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.autostart

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.abid.handlebarlink.autostart.FakeCompanionPresence.Call
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.data.SelectedBike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PresenceArmingTest {
    private val meter = Association(3, "D0:B2:70:42:7F:FB")
    private val other = Association(4, "00:11:22:33:44:55")
    private val presence = FakeCompanionPresence(listOf(meter, other))
    private val settings = InMemoryBikeSettings()

    private fun TestScope.arming() =
        PresenceArming(
            settings = settings,
            presence = presence,
            io = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
            logger = testLogger(RecordingLogSink(), TestTimeProvider(testScheduler)),
        )

    @Test
    fun `nothing happens before start`() =
        runTest {
            settings.bike.value = SelectedBike(meter.address, "HONDA BTU 2040")
            arming()
            runCurrent()

            assertThat(presence.calls).isEmpty()
        }

    @Test
    fun `at start the selected bike is observed and the other association released`() =
        runTest {
            settings.bike.value = SelectedBike(meter.address, "HONDA BTU 2040")
            arming().start()
            runCurrent()

            assertThat(presence.calls).containsExactly(Call.Release(other), Call.Observe(meter))
        }

    @Test
    fun `choosing a bike observes it, forgetting it releases it`() =
        runTest {
            arming().start()
            runCurrent()
            settings.bike.value = SelectedBike(meter.address, "HONDA BTU 2040")
            runCurrent()
            settings.bike.value = null
            runCurrent()

            assertThat(presence.calls).containsExactly(
                // No bike yet: nothing to observe.
                Call.Release(meter),
                Call.Release(other),
                // Chosen.
                Call.Release(other),
                Call.Observe(meter),
                // Forgotten.
                Call.Release(meter),
                Call.Release(other),
            )
        }

    @Test
    fun `a second start does not observe twice`() =
        runTest {
            settings.bike.value = SelectedBike(meter.address, "HONDA BTU 2040")
            val arming = arming()
            arming.start()
            arming.start()
            runCurrent()

            assertThat(presence.calls).containsExactly(Call.Release(other), Call.Observe(meter))
        }
}

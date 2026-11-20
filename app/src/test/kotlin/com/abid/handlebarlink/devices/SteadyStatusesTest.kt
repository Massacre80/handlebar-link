// UnconfinedTestDispatcher and advanceTimeBy are kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.devices

import assertk.assertThat
import assertk.assertions.containsExactly
import com.abid.handlebarlink.core.testing.TestTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The devices' steady live states (docs/PLAN.md "Stable live indicators") on a virtual clock. */
class SteadyStatusesTest {
    private val intercom = "AA:BB:CC:DD:EE:01"
    private val both = mapOf(intercom to DeviceStatus(connected = true, battery = 70, calls = true, music = true))
    private val callsOnly = mapOf(intercom to DeviceStatus(connected = true, battery = 70, calls = true))
    private val none = emptyMap<String, DeviceStatus>()

    private fun timeline(vararg values: Pair<Long, Map<String, DeviceStatus>>): Flow<Map<String, DeviceStatus>> =
        flow {
            var now = 0L
            for ((at, value) in values) {
                delay(at - now)
                now = at
                emit(value)
            }
        }

    private fun TestScope.shown(source: Flow<Map<String, DeviceStatus>>): List<Pair<Long, Map<String, DeviceStatus>>> {
        val time = TestTimeProvider(testScheduler)
        val seen = mutableListOf<Pair<Long, Map<String, DeviceStatus>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.steady(time).collect { seen += time.elapsedMillis() to it }
        }
        // Background work never counts as pending for advanceUntilIdle: run the clock past every timeline instead.
        advanceTimeBy(60_000)
        return seen
    }

    @Test
    fun `a drop of a moment never shows Not connected`() =
        runTest {
            val seen = shown(timeline(0L to both, 10_000L to none, 10_300L to both))
            assertThat(seen).containsExactly(0L to both)
        }

    @Test
    fun `a real disconnect shows after the show delay`() =
        runTest {
            val seen = shown(timeline(0L to both, 10_000L to none, 20_000L to none))
            assertThat(seen).containsExactly(0L to both, 10_400L to none)
        }

    @Test
    fun `a device connecting or adding a profile shows at once`() =
        runTest {
            val seen = shown(timeline(0L to none, 100L to callsOnly, 200L to both))
            assertThat(seen).containsExactly(0L to none, 100L to callsOnly, 200L to both)
        }

    @Test
    fun `a profile dropped for a moment does not flash`() =
        runTest {
            val seen = shown(timeline(0L to both, 5_000L to callsOnly, 5_200L to both))
            assertThat(seen).containsExactly(0L to both)
        }

    @Test
    fun `a battery report replaces the old one at once`() =
        runTest {
            val lower = mapOf(intercom to both.getValue(intercom).copy(battery = 60))
            val seen = shown(timeline(0L to both, 100L to lower))
            assertThat(seen).containsExactly(0L to both, 100L to lower)
        }
}

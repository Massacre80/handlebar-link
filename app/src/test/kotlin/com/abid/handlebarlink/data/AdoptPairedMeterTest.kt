@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.autostart.Association
import com.abid.handlebarlink.autostart.FakeCompanionPresence
import com.abid.handlebarlink.autostart.InMemoryBikeSettings
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Re-adopting the meter from its surviving association after a reinstall: only when nothing is chosen, exactly one. */
class AdoptPairedMeterTest {
    private val meter = Association(id = 1, address = "D0:B2:70:42:7F:FB", name = "HONDA BTU 2040")
    private val other = Association(id = 2, address = "AA:BB:CC:DD:EE:FF", name = "HONDA BTU 9999")

    private fun TestScope.adopt(
        bikes: InMemoryBikeSettings,
        associations: List<Association>,
    ): AdoptPairedMeter {
        val time = TestTimeProvider(testScheduler)
        return AdoptPairedMeter(
            bikes,
            FakeCompanionPresence(associations),
            StandardTestDispatcher(testScheduler),
            backgroundScope,
            testLogger(RecordingLogSink(), time),
        )
    }

    @Test
    fun `no bike and one association re-adopts it with its name`() =
        runTest {
            val bikes = InMemoryBikeSettings()
            adopt(bikes, listOf(meter)).start()
            runCurrent()
            assertThat(bikes.bike.value).isEqualTo(SelectedBike(meter.address, "HONDA BTU 2040"))
        }

    @Test
    fun `an association without a name adopts with a default name`() =
        runTest {
            val bikes = InMemoryBikeSettings()
            adopt(bikes, listOf(meter.copy(name = null))).start()
            runCurrent()
            assertThat(bikes.bike.value).isEqualTo(SelectedBike(meter.address, "Honda meter"))
        }

    @Test
    fun `a bike already chosen is left alone`() =
        runTest {
            val chosen = SelectedBike("AA:BB:CC:DD:EE:FF", "HONDA BTU 9999")
            val bikes = InMemoryBikeSettings().apply { bike.value = chosen }
            adopt(bikes, listOf(meter)).start()
            runCurrent()
            assertThat(bikes.bike.value).isEqualTo(chosen)
        }

    @Test
    fun `no association stays on setup`() =
        runTest {
            val bikes = InMemoryBikeSettings()
            adopt(bikes, emptyList()).start()
            runCurrent()
            assertThat(bikes.bike.value).isNull()
        }

    @Test
    fun `two associations are ambiguous and left to setup`() =
        runTest {
            val bikes = InMemoryBikeSettings()
            adopt(bikes, listOf(meter, other)).start()
            runCurrent()
            assertThat(bikes.bike.value).isNull()
        }

    @Test
    fun `it runs only once per process`() =
        runTest {
            val bikes = InMemoryBikeSettings()
            val adopt = adopt(bikes, listOf(meter))
            adopt.start()
            runCurrent()
            bikes.forget()
            adopt.start()
            runCurrent()
            assertThat(bikes.bike.value).isNull()
        }
}

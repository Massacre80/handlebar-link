// UnconfinedTestDispatcher and runCurrent are kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.ui.carplay

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.CarPlayVideoStatsSource
import com.abid.handlebarlink.carplay.FakeCarPlayPermissions
import com.abid.handlebarlink.carplay.InMemoryCarPlaySettings
import com.abid.handlebarlink.carplay.carPlayCoordinator
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.android.video.CarPlayVideoStats
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.core.testing.MainDispatcherRule
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.setup.SetupConditions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The CarPlay tab's view model: the live state from CarPlay and the iPhones, and its actions. */
class CarPlayViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val abid = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
    private val link = FakeCarPlayLink()
    private val settings = InMemoryCarPlaySettings()
    private val radios = FakeRadios()
    private val pairing = FakeIphonePairing(listOf(abid))
    private val video = MutableStateFlow<CarPlayVideoStats?>(null)

    private fun TestScope.viewModel(): Pair<CarPlayViewModel, com.abid.handlebarlink.carplay.CarPlayCoordinator> {
        val time = TestTimeProvider(testScheduler)
        val coordinator =
            carPlayCoordinator(link, settings, FakeCarPlayPermissions(), testLogger(RecordingLogSink(), time))
        val viewModel =
            CarPlayViewModel(
                coordinator,
                settings,
                pairing,
                CarPlayStatusLines(coordinator, time),
                SteadyLinkQuality(CarPlayVideoStatsSource { video }, time),
                radios,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel to coordinator
    }

    @Test
    fun `the tab follows CarPlay and the iPhones as they change`() =
        runTest {
            val (viewModel, _) = viewModel()
            link.setState(CarPlayState.Connected("phone", Instant.EPOCH))
            runCurrent()
            assertThat(viewModel.uiState.value.hero.phase).isEqualTo(TabPhase.CONNECTED)

            pairing.iphoneList.value = emptyList()
            runCurrent()
            assertThat(viewModel.uiState.value.hero.phase).isEqualTo(TabPhase.NOT_SET_UP)
        }

    @Test
    fun `the Link row follows the group's channel and each window of the picture's numbers`() =
        runTest {
            val (viewModel, _) = viewModel()
            link.setState(CarPlayState.Connected("phone", Instant.EPOCH, wifiChannel = 149))
            runCurrent()
            assertThat(viewModel.uiState.value.link.channel).isEqualTo(149)
            assertThat(viewModel.uiState.value.link.quality).isNull()

            video.value = CarPlayVideoStats(300, 290, 58.0, dropped = 30, decodeP90Millis = 15.0, recoveries = 0)
            runCurrent()
            assertThat(viewModel.uiState.value.link.quality).isEqualTo(LinkQuality.WEAK)
        }

    @Test
    fun `Disconnect stops CarPlay until Start CarPlay`() =
        runTest {
            val (viewModel, coordinator) = viewModel()

            viewModel.stop()
            runCurrent()
            assertThat(coordinator.status.first()).isEqualTo(CarPlayStatus.Held)
            assertThat(viewModel.uiState.value.hero.phase).isEqualTo(TabPhase.HELD)

            viewModel.start()
            runCurrent()
            assertThat(coordinator.status.first()).isEqualTo(CarPlayStatus.Engine(CarPlayState.Stopped))
        }

    @Test
    fun `Bluetooth is read only when CarPlay says it is unavailable, and picks the hero's fix`() =
        runTest {
            val (viewModel, _) = viewModel()
            link.setState(CarPlayState.Connecting(ConnectStep.DIALING_PHONE))
            runCurrent()
            assertThat(radios.reads).isEqualTo(0)

            radios.bluetooth = false
            link.setState(
                CarPlayState.Recovering(CarPlayError.BluetoothUnavailable("Bluetooth is off"), 1, Instant.EPOCH),
            )
            runCurrent()
            assertThat(viewModel.uiState.value.hero.primary).isEqualTo(TabAction.TURN_ON_BLUETOOTH)

            radios.bluetooth = true
            link.setState(
                CarPlayState.Recovering(CarPlayError.BluetoothUnavailable("no paired iPhone"), 2, Instant.EPOCH),
            )
            runCurrent()
            assertThat(viewModel.uiState.value.hero.primary).isEqualTo(TabAction.CHOOSE_IPHONE)
        }

    /** The tab's states with their virtual times, from now on. */
    private fun TestScope.record(viewModel: CarPlayViewModel): List<Pair<Long, CarPlayUiState>> {
        val seen = mutableListOf<Pair<Long, CarPlayUiState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { seen += testScheduler.currentTime to it }
        }
        return seen
    }

    /** One 5 s window of the picture's numbers with [dropped] pictures lost of 300 ([linkQuality]). */
    private fun window(
        dropped: Int,
        recoveries: Int = 0,
    ) = CarPlayVideoStats(300, 300 - dropped, 60.0, dropped = dropped, decodeP90Millis = 12.0, recoveries = recoveries)

    @Test
    fun `a connection that drops for a moment never leaves Connected on the tab`() =
        runTest {
            val (viewModel, _) = viewModel()
            link.setState(CarPlayState.Connected("phone", Instant.EPOCH))
            advanceTimeBy(5_000)
            val seen = record(viewModel)

            link.setState(CarPlayState.Recovering(CarPlayError.LinkFailed("closed"), 1, Instant.EPOCH))
            advanceTimeBy(300)
            link.setState(CarPlayState.Connected("phone", Instant.EPOCH))
            advanceTimeBy(5_000)

            assertThat(seen.map { it.second.hero.phase }.distinct()).containsExactly(TabPhase.CONNECTED)
        }

    @Test
    fun `steps quicker than the show delay never flash, and Connected shows at once`() =
        runTest {
            val (viewModel, _) = viewModel()
            advanceTimeBy(5_000)
            val seen = record(viewModel)
            val before = viewModel.uiState.value.hero.phase

            link.setState(CarPlayState.Idle)
            advanceTimeBy(100)
            link.setState(CarPlayState.Connecting(ConnectStep.PREPARING))
            advanceTimeBy(100)
            link.setState(CarPlayState.Connecting(ConnectStep.DIALING_PHONE))
            advanceTimeBy(100)
            link.setState(CarPlayState.Connected("phone", Instant.EPOCH))
            runCurrent()

            assertThat(seen.map { it.second.hero.phase }.distinct()).containsExactly(before, TabPhase.CONNECTED)
            assertThat(seen.first { it.second.hero.phase == TabPhase.CONNECTED }.first).isEqualTo(5_300L)
        }

    @Test
    fun `a lasting step shows after the show delay`() =
        runTest {
            val (viewModel, _) = viewModel()
            advanceTimeBy(5_000)
            val before = viewModel.uiState.value.hero.phase

            link.setState(CarPlayState.Connecting(ConnectStep.DIALING_PHONE))
            advanceTimeBy(399)
            assertThat(viewModel.uiState.value.hero.phase).isEqualTo(before)
            advanceTimeBy(2)
            assertThat(viewModel.uiState.value.hero.phase).isEqualTo(TabPhase.CONNECTING)
        }

    @Test
    fun `an error only the rider can fix shows at once, even right after a step`() =
        runTest {
            val (viewModel, _) = viewModel()
            link.setState(CarPlayState.Connecting(ConnectStep.DIALING_PHONE))
            advanceTimeBy(5_000)

            link.setState(CarPlayState.Connecting(ConnectStep.IDENTIFYING))
            advanceTimeBy(500)
            link.setState(CarPlayState.Recovering(CarPlayError.WifiOff, 1, Instant.EPOCH))
            runCurrent()

            assertThat(viewModel.uiState.value.hero.phase).isEqualTo(TabPhase.NEEDS_YOU)
        }

    @Test
    fun `one odd window of the picture's numbers never changes the Link row's word`() =
        runTest {
            val (viewModel, _) = viewModel()
            link.setState(CarPlayState.Connected("phone", Instant.EPOCH, wifiChannel = 36))
            video.value = window(dropped = 0)
            runCurrent()
            val seen = record(viewModel)

            // Windows every 5 s: one lost picture (Fair by itself), then clean again, and again.
            for (dropped in listOf(1, 0, 2, 0, 0, 1, 0)) {
                advanceTimeBy(5_000)
                video.value = window(dropped)
            }
            advanceTimeBy(5_000)

            assertThat(seen.map { it.second.link.quality }.distinct()).containsExactly(LinkQuality.STRONG)
        }

    @Test
    fun `a lasting change of quality shows once the next window agrees, Weak at once`() =
        runTest {
            val (viewModel, _) = viewModel()
            link.setState(CarPlayState.Connected("phone", Instant.EPOCH, wifiChannel = 36))
            video.value = window(dropped = 0)
            advanceTimeBy(20_000)

            video.value = window(dropped = 2)
            advanceTimeBy(5_000)
            assertThat(viewModel.uiState.value.link.quality).isEqualTo(LinkQuality.STRONG)
            video.value = window(dropped = 3)
            advanceTimeBy(1_001)
            assertThat(viewModel.uiState.value.link.quality).isEqualTo(LinkQuality.FAIR)

            video.value = window(dropped = 0, recoveries = 1)
            runCurrent()
            assertThat(viewModel.uiState.value.link.quality).isEqualTo(LinkQuality.WEAK)

            // Better news waits two windows on screen.
            video.value = window(dropped = 0)
            advanceTimeBy(9_000)
            assertThat(viewModel.uiState.value.link.quality).isEqualTo(LinkQuality.WEAK)
            advanceTimeBy(1_001)
            assertThat(viewModel.uiState.value.link.quality).isEqualTo(LinkQuality.STRONG)
        }

    /** Bluetooth's state as the test sets it, counting the reads. */
    private class FakeRadios : SetupConditions {
        var bluetooth = true
        var reads = 0

        override fun bluetoothOn(): Boolean {
            reads++
            return bluetooth
        }

        override fun locationOn() = true
    }
}

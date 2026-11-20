// UnconfinedTestDispatcher and runCurrent are kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.ui.carplay

import assertk.assertThat
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
                CarPlayVideoStatsSource { video },
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

// UnconfinedTestDispatcher and runCurrent are kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.ui.carplay

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.FakeCarPlayPermissions
import com.abid.handlebarlink.carplay.InMemoryCarPlaySettings
import com.abid.handlebarlink.carplay.carPlayCoordinator
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.core.testing.MainDispatcherRule
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The CarPlay tab's view model: the live state from the switch, CarPlay and the iPhones, and its actions. */
class CarPlayViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val abid = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
    private val link = FakeCarPlayLink()
    private val settings = InMemoryCarPlaySettings(enabled = true)
    private val pairing = FakeIphonePairing(listOf(abid))

    private fun TestScope.viewModel(): Pair<CarPlayViewModel, com.abid.handlebarlink.carplay.CarPlayCoordinator> {
        val time = TestTimeProvider(testScheduler)
        val coordinator =
            carPlayCoordinator(link, settings, FakeCarPlayPermissions(), testLogger(RecordingLogSink(), time))
        val viewModel = CarPlayViewModel(coordinator, settings, pairing, CarPlayStatusLines(coordinator, time))
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
    fun `Disconnect stops CarPlay until Start CarPlay, which turns the switch on`() =
        runTest {
            val (viewModel, coordinator) = viewModel()

            viewModel.stop()
            runCurrent()
            assertThat(coordinator.status.first()).isEqualTo(CarPlayStatus.Held)
            assertThat(viewModel.uiState.value.hero.phase).isEqualTo(TabPhase.HELD)

            settings.setEnabled(false)
            viewModel.start()
            runCurrent()
            assertThat(settings.enabled.value).isEqualTo(true)
            assertThat(coordinator.status.first()).isEqualTo(CarPlayStatus.Engine(CarPlayState.Stopped))
        }
}

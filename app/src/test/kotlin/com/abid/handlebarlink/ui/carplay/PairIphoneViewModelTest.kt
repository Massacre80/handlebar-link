// UnconfinedTestDispatcher, runCurrent and advanceTimeBy are kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.ui.carplay

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.FakeCarPlayPermissions
import com.abid.handlebarlink.carplay.InMemoryCarPlaySettings
import com.abid.handlebarlink.carplay.carPlayCoordinator
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.android.pairing.PairingProblem
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.core.testing.MainDispatcherRule
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** In-app iPhone pairing (Paper "CarPlay — flow v2", iPhone 1–3) over a fake pairing session. */
class PairIphoneViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val abid = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
    private val pairing = FakeIphonePairing()
    private val settings = InMemoryCarPlaySettings()
    private lateinit var collector: Job
    private lateinit var coordinator: CarPlayCoordinator

    private fun TestScope.viewModel(): PairIphoneViewModel {
        val time = TestTimeProvider(testScheduler)
        coordinator =
            carPlayCoordinator(
                FakeCarPlayLink(),
                settings,
                FakeCarPlayPermissions(),
                testLogger(RecordingLogSink(), time),
            )
        val viewModel = PairIphoneViewModel(pairing, coordinator, time)
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private val PairIphoneViewModel.state get() = uiState.value

    @Test
    fun `it opens on the steps, waiting for this phone to be made visible`() =
        runTest {
            val viewModel = viewModel()
            assertThat(viewModel.state.step).isEqualTo(PairStep.STEPS)
            assertThat(viewModel.state.problem).isNull()
            assertThat(viewModel.state.secondsLeft).isNull()
        }

    @Test
    fun `made visible it waits with this phone's name and counts the five minutes down to zero`() =
        runTest {
            val viewModel = viewModel()

            viewModel.visible(300)
            runCurrent()
            assertThat(viewModel.state.step).isEqualTo(PairStep.WAITING)
            assertThat(viewModel.state.localName).isEqualTo("POCO F1")
            assertThat(viewModel.state.secondsLeft).isEqualTo(300L)

            advanceTimeBy(61_000)
            runCurrent()
            assertThat(viewModel.state.secondsLeft).isEqualTo(239L)

            advanceTimeBy(240_000)
            runCurrent()
            assertThat(viewModel.state.secondsLeft).isEqualTo(0L)
            assertThat(viewModel.state.step).isEqualTo(PairStep.WAITING)
        }

    @Test
    fun `refusing Android's dialog stays on the steps with a problem to retry`() =
        runTest {
            val viewModel = viewModel()
            viewModel.notVisible()
            runCurrent()
            assertThat(viewModel.state.step).isEqualTo(PairStep.STEPS)
            assertThat(viewModel.state.problem).isEqualTo(PairProblem.NOT_VISIBLE)

            viewModel.visible(300)
            runCurrent()
            assertThat(viewModel.state.problem).isNull()
        }

    @Test
    fun `a bond forming shows the code screen, and a paired iPhone becomes CarPlay's, chosen once`() =
        runTest {
            val viewModel = viewModel()
            viewModel.visible(300)
            pairing.sessionState.value = IphonePairingState.Pairing(abid.address, abid.name)
            runCurrent()
            assertThat(viewModel.state.step).isEqualTo(PairStep.PAIRING)
            assertThat(viewModel.state.otherName).isEqualTo("Abid's iPhone")
            assertThat(pairing.chosenCalls).isEmpty()

            pairing.sessionState.value = IphonePairingState.Paired(abid)
            runCurrent()
            assertThat(viewModel.state.step).isEqualTo(PairStep.PAIRED)
            assertThat(viewModel.state.paired).isEqualTo(abid)
            assertThat(pairing.chosenCalls).containsExactly(abid.address)

            // The screen left long enough for the session to stop, and came back: the session starts again and
            // reports the same iPhone, which is not chosen twice.
            collector.cancel()
            advanceTimeBy(6_000)
            runCurrent()
            assertThat(pairing.sessionsActive).isEqualTo(0)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            runCurrent()
            assertThat(pairing.sessionsStarted).isEqualTo(2)
            assertThat(viewModel.state.step).isEqualTo(PairStep.PAIRED)
            assertThat(pairing.chosenCalls).containsExactly(abid.address)
        }

    @Test
    fun `a pairing that did not form and a phone that is not an iPhone are shown on the waiting screen`() =
        runTest {
            val viewModel = viewModel()
            viewModel.visible(300)
            pairing.sessionState.value = IphonePairingState.Waiting("POCO F1", PairingProblem.Failed("Abid's iPhone"))
            runCurrent()
            assertThat(viewModel.state.step).isEqualTo(PairStep.WAITING)
            assertThat(viewModel.state.problem).isEqualTo(PairProblem.FAILED)
            assertThat(viewModel.state.otherName).isEqualTo("Abid's iPhone")

            pairing.sessionState.value = IphonePairingState.Waiting("POCO F1", PairingProblem.NotAnIphone("Galaxy"))
            runCurrent()
            assertThat(viewModel.state.problem).isEqualTo(PairProblem.NOT_AN_IPHONE)
            assertThat(pairing.chosenCalls).isEmpty()
        }

    @Test
    fun `no permission and Bluetooth off are problems, and the session restarts after the permission`() =
        runTest {
            pairing.sessionState.value = IphonePairingState.NotAllowed
            val viewModel = viewModel()
            assertThat(viewModel.state.problem).isEqualTo(PairProblem.NOT_ALLOWED)
            assertThat(pairing.sessionsStarted).isEqualTo(1)

            pairing.sessionState.value = IphonePairingState.BluetoothOff
            viewModel.restart()
            runCurrent()
            assertThat(pairing.sessionsStarted).isEqualTo(2)
            assertThat(pairing.sessionsActive).isEqualTo(1)
            assertThat(viewModel.state.problem).isEqualTo(PairProblem.BLUETOOTH_OFF)
        }

    @Test
    fun `Start CarPlay lifts the rider's stop, and rename asks for the bike's name`() =
        runTest {
            val viewModel = viewModel()
            coordinator.hold()
            viewModel.startCarPlay()
            viewModel.rename("CB350RS")
            runCurrent()
            assertThat(coordinator.stoppedByRider.value).isEqualTo(false)
            assertThat(pairing.renamed).containsExactly("CB350RS")
        }

    @Test
    fun `the time left is shown as minutes and seconds`() {
        assertThat(minutesSeconds(300)).isEqualTo("5:00")
        assertThat(minutesSeconds(59)).isEqualTo("0:59")
        assertThat(minutesSeconds(0)).isEqualTo("0:00")
    }
}

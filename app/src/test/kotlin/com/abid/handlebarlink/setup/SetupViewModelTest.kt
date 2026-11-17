// Virtual-time controls of kotlinx-coroutines-test (runCurrent, advanceTimeBy).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.setup

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.core.testing.MainDispatcherRule
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.SelectedBike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Robolectric only to create a real IntentSender; everything else is faked.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SetupViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val meter = FoundMeter("HONDA BTU 2040", "D0:B2:70:42:7F:FB", -52)
    private val conditions = FakeConditions()
    private val found = MutableSharedFlow<List<FoundMeter>>(replay = 1)
    private var scanning = false
    private var scanFailure: SetupException? = null
    private val consent: IntentSender =
        PendingIntent
            .getActivity(RuntimeEnvironment.getApplication(), 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
            .intentSender
    private var linkFailure: SetupException? = null
    private var linkedAddress: String? = meter.address
    private val bondResults = ArrayDeque(listOf(true))
    private val bikes = FakeBikes()

    private fun TestScope.viewModel() =
        SetupViewModel(
            conditions = conditions,
            scanner = MeterScanner { scan() },
            association =
                object : MeterAssociation {
                    override suspend fun request(meter: FoundMeter): IntentSender =
                        linkFailure?.let { throw it } ?: consent

                    override fun addressFrom(
                        resultCode: Int,
                        data: Intent?,
                    ): String? = if (resultCode == Activity.RESULT_OK) linkedAddress else null
                },
            bonding = MeterBonding { bondResults.removeFirst() },
            bikes = bikes,
            logger = testLogger(RecordingLogSink(), TestTimeProvider(testScheduler)),
        )

    private fun scan(): Flow<List<FoundMeter>> {
        scanFailure?.let { throw it }
        scanning = true
        return found.onCompletion { scanning = false }
    }

    private fun TestScope.ready() = viewModel().also { it.start() }

    @Test
    fun `welcome leads to the meter steps, and Back returns`() =
        runTest(main.dispatcher.scheduler) {
            val vm = viewModel()
            vm.start()
            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.READY)

            assertThat(vm.back()).isTrue()
            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.WELCOME)
            assertThat(vm.back()).isFalse()
        }

    @Test
    fun `searching needs Bluetooth, then Location Services`() =
        runTest(main.dispatcher.scheduler) {
            val vm = ready()
            conditions.bluetooth = false
            vm.findBike()
            assertThat(vm.uiState.value.problem).isEqualTo(SetupProblem.BLUETOOTH_OFF)

            conditions.bluetooth = true
            conditions.location = false
            vm.findBike()

            assertThat(vm.uiState.value.problem).isEqualTo(SetupProblem.LOCATION_OFF)
            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.READY)
            assertThat(scanning).isFalse()
        }

    @Test
    fun `found meters are listed, and the search stops after its time limit`() =
        runTest(main.dispatcher.scheduler) {
            val vm = ready()
            vm.findBike()
            runCurrent()
            found.emit(listOf(meter))
            runCurrent()

            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.CHOOSE)
            assertThat(vm.uiState.value.meters).containsExactly(meter)
            assertThat(vm.uiState.value.searching).isTrue()

            advanceTimeBy(15_001)
            runCurrent()

            assertThat(vm.uiState.value.searching).isFalse()
            assertThat(scanning).isFalse()
        }

    @Test
    fun `a failed scan is shown`() =
        runTest(main.dispatcher.scheduler) {
            scanFailure = SetupException(SetupProblem.SCAN_FAILED)
            val vm = ready()

            vm.findBike()
            runCurrent()

            assertThat(vm.uiState.value.problem).isEqualTo(SetupProblem.SCAN_FAILED)
            assertThat(vm.uiState.value.searching).isFalse()
        }

    @Test
    fun `choosing a meter stops the scan and asks Android to link it`() =
        runTest(main.dispatcher.scheduler) {
            val vm = ready()
            vm.findBike()
            runCurrent()

            vm.choose(meter)
            runCurrent()

            assertThat(scanning).isFalse()
            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.LINKING)
            assertThat(vm.uiState.value.consent).isEqualTo(consent)
            vm.consentLaunched()
            assertThat(vm.uiState.value.consent).isNull()
        }

    @Test
    fun `when Android cannot link, the rider is back at the list with a reason`() =
        runTest(main.dispatcher.scheduler) {
            linkFailure = SetupException(SetupProblem.NOT_LINKED)
            val vm = ready()

            vm.choose(meter)
            runCurrent()

            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.CHOOSE)
            assertThat(vm.uiState.value.problem).isEqualTo(SetupProblem.NOT_LINKED)
        }

    @Test
    fun `a refused consent returns to the list`() =
        runTest(main.dispatcher.scheduler) {
            val vm = ready()
            vm.choose(meter)
            runCurrent()

            vm.onConsentResult(Activity.RESULT_CANCELED, null)

            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.CHOOSE)
            assertThat(vm.uiState.value.problem).isEqualTo(SetupProblem.NOT_LINKED)
        }

    @Test
    fun `linked and paired leads to You're set, and the bike is saved only on Go to Home`() =
        runTest(main.dispatcher.scheduler) {
            val vm = ready()
            vm.choose(meter)
            runCurrent()

            vm.onConsentResult(Activity.RESULT_OK, null)
            runCurrent()

            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.DONE)
            assertThat(bikes.bike.value).isNull()

            vm.finish()
            runCurrent()

            assertThat(bikes.bike.value).isEqualTo(SelectedBike(meter.address, meter.name))
        }

    @Test
    fun `a failed pairing can be tried again`() =
        runTest(main.dispatcher.scheduler) {
            bondResults.clear()
            bondResults.addAll(listOf(false, true))
            val vm = ready()
            vm.choose(meter)
            runCurrent()
            vm.onConsentResult(Activity.RESULT_OK, null)
            runCurrent()
            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.PAIRING)
            assertThat(vm.uiState.value.problem).isEqualTo(SetupProblem.PAIRING_FAILED)

            vm.pair()
            runCurrent()

            assertThat(vm.uiState.value.step).isEqualTo(SetupStep.DONE)
            assertThat(vm.uiState.value.problem).isNull()
        }

    @Test
    fun `a refused permission is shown`() =
        runTest(main.dispatcher.scheduler) {
            val vm = ready()

            vm.permissionRefused()

            assertThat(vm.uiState.value.problem).isNotNull()
            assertThat(vm.uiState.value.problem).isEqualTo(SetupProblem.PERMISSION_MISSING)
        }

    private class FakeConditions : SetupConditions {
        var bluetooth = true
        var location = true

        override fun bluetoothOn() = bluetooth

        override fun locationOn() = location
    }

    private class FakeBikes : BikeSettings {
        override val bike = MutableStateFlow<SelectedBike?>(null)

        override suspend fun select(bike: SelectedBike) {
            this.bike.value = bike
        }

        override suspend fun forget() {
            bike.value = null
        }
    }
}

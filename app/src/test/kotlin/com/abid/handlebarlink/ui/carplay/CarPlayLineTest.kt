// Virtual-time controls of kotlinx-coroutines-test (runCurrent, advanceTimeBy).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.ui.carplay

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.StallVerdict
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.testing.TestTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class CarPlayLineTest {
    private val now = Instant.parse("2026-11-19T10:00:00Z")

    private fun engine(state: CarPlayState) = carPlayLine(CarPlayStatus.Engine(state), now)

    @Test
    fun `the app's own states have their lines, and off keeps CarLink's`() {
        assertThat(carPlayLine(CarPlayStatus.Off, now))
            .isEqualTo(CarPlayLine(R.string.carplay_interim, tone = SignalTone.IDLE))
        assertThat(carPlayLine(CarPlayStatus.NotAvailable, now))
            .isEqualTo(CarPlayLine(R.string.carplay_status_not_available, tone = SignalTone.IDLE))
        assertThat(carPlayLine(CarPlayStatus.NeedsPermission(listOf("p")), now))
            .isEqualTo(CarPlayLine(R.string.carplay_status_permission, tone = SignalTone.ERROR))
        assertThat(carPlayLine(CarPlayStatus.Failed, now))
            .isEqualTo(CarPlayLine(R.string.carplay_status_failed, tone = SignalTone.ERROR))
    }

    @Test
    fun `each engine state has its line`() {
        assertThat(engine(CarPlayState.Stopped))
            .isEqualTo(CarPlayLine(R.string.carplay_status_waiting, tone = SignalTone.IDLE))
        assertThat(engine(CarPlayState.Idle))
            .isEqualTo(CarPlayLine(R.string.carplay_status_starting, tone = SignalTone.PROGRESS))
        assertThat(engine(CarPlayState.Connecting(ConnectStep.DIALING_PHONE))).isEqualTo(
            CarPlayLine(R.string.carplay_status_connecting, R.string.carplay_step_dialing, tone = SignalTone.PROGRESS),
        )
        assertThat(engine(CarPlayState.Connected("phone", now)))
            .isEqualTo(CarPlayLine(R.string.carplay_status_connected, tone = SignalTone.ON))
    }

    @Test
    fun `each connection step has its own words`() {
        val expected =
            mapOf(
                ConnectStep.LOADING_IDENTITY to R.string.carplay_step_identity,
                ConnectStep.PREPARING to R.string.carplay_step_preparing,
                ConnectStep.DIALING_PHONE to R.string.carplay_step_dialing,
                ConnectStep.IDENTIFYING to R.string.carplay_step_identifying,
                ConnectStep.AUTHENTICATING to R.string.carplay_step_authenticating,
                ConnectStep.WAITING_FOR_PHONE to R.string.carplay_step_waiting,
                ConnectStep.PAIRING to R.string.carplay_step_pairing,
            )

        assertThat(expected.keys).isEqualTo(ConnectStep.entries.toSet())
        expected.forEach { (step, text) -> assertThat(stepText(step), step.name).isEqualTo(text) }
    }

    @Test
    fun `each error has its own words`() {
        val expected =
            listOf(
                CarPlayError.IdentityUnavailable("no files") to R.string.carplay_error_identity,
                CarPlayError.BluetoothUnavailable("off") to R.string.carplay_error_bluetooth,
                CarPlayError.WifiDirectFailed("busy") to R.string.carplay_error_wifi_direct,
                CarPlayError.NetworkFailed("bind") to R.string.carplay_error_network,
                CarPlayError.DialFailed("refused") to R.string.carplay_error_dial,
                CarPlayError.LinkFailed("closed") to R.string.carplay_error_link,
                CarPlayError.IdentificationRejected to R.string.carplay_error_identification,
                CarPlayError.AuthenticationFailed to R.string.carplay_error_authentication,
                CarPlayError.TimedOut(ConnectStep.PAIRING) to R.string.carplay_error_timed_out,
                CarPlayError.PhoneDisconnected("gone") to R.string.carplay_error_disconnected,
                CarPlayError.PermissionMissing("android.permission.BLUETOOTH_CONNECT") to
                    R.string.carplay_error_permission,
                CarPlayError.WifiOff to R.string.carplay_error_wifi_off,
                CarPlayError.Stalled(StallVerdict.NOT_JOINED) to R.string.carplay_error_stalled_not_joined,
                CarPlayError.Stalled(StallVerdict.NO_START_REQUEST) to R.string.carplay_error_stalled_no_start,
                CarPlayError.Stalled(StallVerdict.NO_TCP) to R.string.carplay_error_stalled_no_tcp,
                CarPlayError.Unexpected(IllegalStateException()) to R.string.carplay_error_unexpected,
            )

        // errorText's `when` is exhaustive: a new kind of error does not compile until it has words. Each has its own.
        assertThat(expected.map { it.second }.toSet()).hasSize(expected.size)
        expected.forEach { (error, text) -> assertThat(errorText(error), error.toString()).isEqualTo(text) }
    }

    @Test
    fun `a passing failure says what failed and counts down to the retry, rounded up`() {
        val error = CarPlayError.WifiDirectFailed("busy")

        assertThat(engine(CarPlayState.Recovering(error, 2, now.plusMillis(4_200)))).isEqualTo(
            CarPlayLine(R.string.carplay_status_retrying, R.string.carplay_error_wifi_direct, 5, SignalTone.PROGRESS),
        )
        assertThat(engine(CarPlayState.Recovering(error, 2, now.plusMillis(4_000))).seconds).isEqualTo(4L)
        // A retry that is due is about to start: never "0 s" or less.
        assertThat(engine(CarPlayState.Recovering(error, 2, now.minusMillis(500))).seconds).isEqualTo(1L)
    }

    @Test
    fun `a failure only the rider can fix shows the error alone`() {
        val error = CarPlayError.BluetoothUnavailable("off")

        assertThat(engine(CarPlayState.Recovering(error, 1, now.plusSeconds(30))))
            .isEqualTo(CarPlayLine(R.string.carplay_error_bluetooth, tone = SignalTone.ERROR))
    }

    @Test
    fun `the countdown ticks every second while retrying and stops when the state moves on`() =
        runTest {
            val time = TestTimeProvider(testScheduler)
            val start = time.clock.instant()
            val error = CarPlayError.DialFailed("refused")
            val status =
                MutableStateFlow<CarPlayStatus>(
                    CarPlayStatus.Engine(CarPlayState.Recovering(error, 1, start.plusSeconds(3))),
                )
            val seconds = mutableListOf<Long?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                carPlayLines(status, time).collect { seconds += it.seconds }
            }

            advanceTimeBy(2_500)
            status.value = CarPlayStatus.Engine(CarPlayState.Idle)
            advanceTimeBy(5_000)
            runCurrent()

            assertThat(seconds).containsExactly(3L, 2L, 1L, null)
        }
}

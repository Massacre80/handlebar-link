package com.abid.handlebarlink.ui.carplay

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.StallVerdict
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.time.Duration
import java.time.Instant

/**
 * CarPlay's one status line in plain words ([text], with the step or error [part] and the [seconds] to the next
 * try where it has them), and the [tone] of Home's CarPlay card.
 */
@Immutable
data class CarPlayLine(
    @param:StringRes val text: Int,
    @param:StringRes val part: Int? = null,
    val seconds: Long? = null,
    val tone: SignalTone,
)

/** The line's words. */
@Composable
fun CarPlayLine.words(): String =
    when {
        part != null && seconds != null -> stringResource(text, stringResource(part), seconds)
        part != null -> stringResource(text, stringResource(part))
        else -> stringResource(text)
    }

/**
 * The line for [status] at [now]: Off keeps "Runs in CarLink for now"; then Starting, "Connecting · step",
 * Connected, "error · Retrying in N s", or the error alone when only the rider can fix it.
 */
internal fun carPlayLine(
    status: CarPlayStatus,
    now: Instant,
): CarPlayLine =
    when (status) {
        CarPlayStatus.Off -> CarPlayLine(R.string.carplay_interim, tone = SignalTone.IDLE)
        CarPlayStatus.NotAvailable -> CarPlayLine(R.string.carplay_status_not_available, tone = SignalTone.IDLE)
        is CarPlayStatus.NeedsPermission -> CarPlayLine(R.string.carplay_status_permission, tone = SignalTone.ERROR)
        CarPlayStatus.Failed -> CarPlayLine(R.string.carplay_status_failed, tone = SignalTone.ERROR)
        is CarPlayStatus.Engine -> engineLine(status.state, now)
    }

private fun engineLine(
    state: CarPlayState,
    now: Instant,
): CarPlayLine =
    when (state) {
        CarPlayState.Stopped -> {
            CarPlayLine(R.string.carplay_status_waiting, tone = SignalTone.IDLE)
        }

        CarPlayState.Idle -> {
            CarPlayLine(R.string.carplay_status_starting, tone = SignalTone.PROGRESS)
        }

        is CarPlayState.Connecting -> {
            CarPlayLine(R.string.carplay_status_connecting, stepText(state.step), tone = SignalTone.PROGRESS)
        }

        is CarPlayState.Connected -> {
            CarPlayLine(R.string.carplay_status_connected, tone = SignalTone.ON)
        }

        is CarPlayState.Recovering -> {
            if (state.error.needsUser) {
                CarPlayLine(errorText(state.error), tone = SignalTone.ERROR)
            } else {
                CarPlayLine(
                    R.string.carplay_status_retrying,
                    errorText(state.error),
                    secondsUntil(now, state.retryAt),
                    SignalTone.PROGRESS,
                )
            }
        }
    }

/** Whole seconds to [then], rounded up and at least one (a retry that is due is about to start). */
private fun secondsUntil(
    now: Instant,
    then: Instant,
): Long {
    val millis = Duration.between(now, then).toMillis()
    return ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(1)
}

/** What the receiver is doing in [step], in the rider's words. */
@StringRes
internal fun stepText(step: ConnectStep): Int =
    when (step) {
        ConnectStep.LOADING_IDENTITY -> R.string.carplay_step_identity
        ConnectStep.PREPARING -> R.string.carplay_step_preparing
        ConnectStep.DIALING_PHONE -> R.string.carplay_step_dialing
        ConnectStep.IDENTIFYING -> R.string.carplay_step_identifying
        ConnectStep.AUTHENTICATING -> R.string.carplay_step_authenticating
        ConnectStep.WAITING_FOR_PHONE -> R.string.carplay_step_waiting
        ConnectStep.PAIRING -> R.string.carplay_step_pairing
    }

/** What went wrong, in the rider's words. */
@StringRes
internal fun errorText(error: CarPlayError): Int =
    when (error) {
        is CarPlayError.IdentityUnavailable -> R.string.carplay_error_identity
        is CarPlayError.BluetoothUnavailable -> R.string.carplay_error_bluetooth
        is CarPlayError.WifiDirectFailed -> R.string.carplay_error_wifi_direct
        is CarPlayError.NetworkFailed -> R.string.carplay_error_network
        is CarPlayError.DialFailed -> R.string.carplay_error_dial
        is CarPlayError.LinkFailed -> R.string.carplay_error_link
        CarPlayError.IdentificationRejected -> R.string.carplay_error_identification
        CarPlayError.AuthenticationFailed -> R.string.carplay_error_authentication
        is CarPlayError.TimedOut -> R.string.carplay_error_timed_out
        is CarPlayError.PhoneDisconnected -> R.string.carplay_error_disconnected
        is CarPlayError.PermissionMissing -> R.string.carplay_error_permission
        CarPlayError.WifiOff -> R.string.carplay_error_wifi_off
        is CarPlayError.Stalled -> stallText(error.verdict)
        is CarPlayError.Unexpected -> R.string.carplay_error_unexpected
    }

/** Where a wireless start stalled (the engine's link watch), in the rider's words. */
@StringRes
private fun stallText(verdict: StallVerdict): Int =
    when (verdict) {
        StallVerdict.NOT_JOINED -> R.string.carplay_error_stalled_not_joined
        StallVerdict.NO_START_REQUEST -> R.string.carplay_error_stalled_no_start
        StallVerdict.NO_TCP -> R.string.carplay_error_stalled_no_tcp
    }

/**
 * The line for each [status], ticking every second while a retry counts down (the clock from [time], so tests run
 * on virtual time).
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
fun carPlayLines(
    status: Flow<CarPlayStatus>,
    time: TimeProvider,
): Flow<CarPlayLine> =
    status
        .flatMapLatest { current ->
            if (current is CarPlayStatus.Engine && current.state is CarPlayState.Recovering) {
                flow {
                    while (true) {
                        emit(carPlayLine(current, time.clock.instant()))
                        delay(MILLIS_PER_SECOND)
                    }
                }
            } else {
                flowOf(carPlayLine(current, time.clock.instant()))
            }
        }.distinctUntilChanged()

private const val MILLIS_PER_SECOND = 1_000L

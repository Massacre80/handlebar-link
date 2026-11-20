package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.steady.STATE_MIN_SHOW_MS
import com.abid.handlebarlink.steady.STATE_SHOW_DELAY_MS
import com.abid.handlebarlink.steady.stableState
import kotlinx.coroutines.flow.Flow

/** Where our own CarPlay receiver stands, for the CarPlay page and Home's card. */
sealed interface CarPlayStatus {
    /** This build has no platform ports for the receiver ([CarPlayPortsSource] returned null). */
    data object NotAvailable : CarPlayStatus

    /** Runtime [permissions] are missing (app-side: CarPlay does not start without them). */
    data class NeedsPermission(
        val permissions: List<String>,
    ) : CarPlayStatus

    /** The receiver stopped with an unexpected error; Start CarPlay starts it again ([CarPlayCoordinator.start]). */
    data object Failed : CarPlayStatus

    /** The rider stopped CarPlay (Cancel or Disconnect on the CarPlay tab) until they start it again. */
    data object Held : CarPlayStatus

    /** Allowed: what the engine reports ([CarPlayState.Stopped] while no ride runs). */
    data class Engine(
        val state: CarPlayState,
    ) : CarPlayStatus
}

/**
 * The status from whether ports exist ([available]), whether the receiver [failed], the [missing] permissions, and
 * the engine's [state]; in that order of priority. [held]: the rider stopped CarPlay; it ranks after a failure and
 * missing permissions, which the rider must still see.
 */
internal fun carPlayStatus(
    available: Boolean,
    missing: List<String>,
    failed: Boolean,
    state: CarPlayState,
    held: Boolean = false,
): CarPlayStatus =
    when {
        !available -> CarPlayStatus.NotAvailable
        failed -> CarPlayStatus.Failed
        missing.isNotEmpty() -> CarPlayStatus.NeedsPermission(missing)
        held -> CarPlayStatus.Held
        else -> CarPlayStatus.Engine(state)
    }

/**
 * [this] as the screens show it (docs/PLAN.md "Stable live indicators", [stableState]): a step or state that lasts
 * less than [STATE_SHOW_DELAY_MS] never shows, and a shown one stays [STATE_MIN_SHOW_MS] before the next ordinary
 * change. Shown at once: Connected, an error only the rider can fix, the app's own states (no receiver, permissions,
 * failed, stopped by the rider), and anything that follows them (the answer to Start CarPlay or a permission granted).
 * A connection that drops for a moment and comes back shows nothing.
 */
fun Flow<CarPlayStatus>.steady(time: TimeProvider): Flow<CarPlayStatus> =
    stableState(
        time,
        stateOf = ::shownState,
        immediate = { shown, next -> shown !is CarPlayStatus.Engine || atOnce(next) },
    )

/**
 * Which state [status] shows; new values of the same state replace it at once (a later Wi-Fi channel, the next
 * retry's countdown of the same error).
 */
internal fun shownState(status: CarPlayStatus): Any =
    if (status is CarPlayStatus.Engine) {
        when (val state = status.state) {
            is CarPlayState.Connecting -> state.step
            is CarPlayState.Connected -> CarPlayState.Connected::class
            is CarPlayState.Recovering -> state.error::class
            CarPlayState.Idle, CarPlayState.Stopped -> state
        }
    } else {
        status
    }

/** Shown without waiting: good news, things only the rider can fix, and the app's own states. */
private fun atOnce(next: CarPlayStatus): Boolean {
    val state = (next as? CarPlayStatus.Engine)?.state ?: return true
    return state is CarPlayState.Connected || (state is CarPlayState.Recovering && state.error.needsUser)
}

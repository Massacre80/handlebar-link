package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.CarPlayState

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

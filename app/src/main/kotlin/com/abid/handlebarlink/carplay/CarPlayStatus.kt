package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.CarPlayState

/** Where our own CarPlay receiver stands, for the CarPlay page and Home's card. */
sealed interface CarPlayStatus {
    /** The beta switch is off: CarLink runs CarPlay. */
    data object Off : CarPlayStatus

    /** On, but this build has no platform ports for the receiver ([CarPlayPortsSource] returned null). */
    data object NotAvailable : CarPlayStatus

    /** On, but runtime [permissions] are missing (app-side: the engine has no error for it yet). */
    data class NeedsPermission(
        val permissions: List<String>,
    ) : CarPlayStatus

    /** On, but the receiver stopped with an unexpected error; turning the switch off and on starts it again. */
    data object Failed : CarPlayStatus

    /** On, but the rider stopped CarPlay (Cancel or Disconnect on the CarPlay tab) until they start it again. */
    data object Held : CarPlayStatus

    /** On and allowed: what the engine reports ([CarPlayState.Stopped] while no ride runs). */
    data class Engine(
        val state: CarPlayState,
    ) : CarPlayStatus
}

/**
 * The status from the switch ([enabled]), whether ports exist ([available]), the [missing] permissions, whether
 * the receiver [failed], and the engine's [state]; in that order of priority. [held]: the rider stopped CarPlay;
 * it ranks after a failure and missing permissions, which the rider must still see.
 */
internal fun carPlayStatus(
    enabled: Boolean,
    available: Boolean,
    missing: List<String>,
    failed: Boolean,
    state: CarPlayState,
    held: Boolean = false,
): CarPlayStatus =
    when {
        !enabled -> CarPlayStatus.Off
        !available -> CarPlayStatus.NotAvailable
        failed -> CarPlayStatus.Failed
        missing.isNotEmpty() -> CarPlayStatus.NeedsPermission(missing)
        held -> CarPlayStatus.Held
        else -> CarPlayStatus.Engine(state)
    }

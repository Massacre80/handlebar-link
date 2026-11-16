package com.abid.handlebarlink.controls

import com.abid.handlebarlink.bike.protocol.HandlebarSwitch

/** What a handlebar press asks CarPlay to do (docs/research/01-architecture.md §2.4). */
enum class ControlAction { PLAY_PAUSE, NEXT, PREVIOUS }

/** Why an action could not be performed. */
enum class Unavailable {
    /** The app may not read media sessions (notification access is off). */
    NO_MEDIA_ACCESS,

    /** CarPlay has no music session yet (nothing has played since it connected). */
    NO_CARPLAY_SESSION,
}

/** The outcome of an action. */
sealed interface ControlResult {
    data object Done : ControlResult

    data class NotDone(
        val reason: Unavailable,
    ) : ControlResult
}

/** Where actions go. Today: CarLink's CarPlay media session; later our own CarPlay engine. */
fun interface ControlTarget {
    /** Performs [action]; never throws. */
    fun perform(action: ControlAction): ControlResult
}

/**
 * The handlebar mapping (codes verified on the CB350, SAB_PROTOCOL.md "Verified"). Left and hold-left
 * are free until our own CarPlay receiver can open Siri (PLAN, CarPlay phase).
 */
object ControlRouter {
    /** The action for [switch], or null when the press is not mapped. */
    fun actionFor(switch: HandlebarSwitch): ControlAction? =
        when (switch) {
            HandlebarSwitch.UP -> ControlAction.PREVIOUS
            HandlebarSwitch.DOWN -> ControlAction.NEXT
            HandlebarSwitch.RIGHT -> ControlAction.PLAY_PAUSE
            HandlebarSwitch.HOLD_DOWN -> ControlAction.PLAY_PAUSE
            HandlebarSwitch.LEFT, HandlebarSwitch.HOLD_LEFT -> null
        }
}

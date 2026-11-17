package com.abid.handlebarlink.controls

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

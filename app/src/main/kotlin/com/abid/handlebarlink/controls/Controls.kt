package com.abid.handlebarlink.controls

/**
 * What a handlebar press asks CarPlay to do (docs/research/01-architecture.md §2.4). The call and Siri actions
 * are the seam for our own CarPlay engine (docs/PLAN.md "Smart buttons"), which will send them as:
 * [ANSWER_CALL] the telephony HID hook switch, [DECLINE_CALL] and [END_CALL] the telephony HID drop
 * (DiPlay `CarPlayController.kt:750-753`), [SIRI] the AirPlay command `requestSiri` (siriAction 2, then 3;
 * DiPlay `AirPlaySession.kt:391-392`). CarLink cannot receive them from another app (docs/LESSONS.md 44).
 */
enum class ControlAction {
    /** CarPlay's music: play or pause. */
    PLAY_PAUSE,

    /** CarPlay's music: next track. */
    NEXT,

    /** CarPlay's music: previous track. */
    PREVIOUS,

    /** Picks up the ringing call (HID hook switch). */
    ANSWER_CALL,

    /** Turns the ringing call down (HID drop while ringing). */
    DECLINE_CALL,

    /** Hangs up the call (HID drop). */
    END_CALL,

    /** Starts Siri on the iPhone (AirPlay `requestSiri`). */
    SIRI,
}

/** Why an action could not be performed. The UI words each reason; the logic never holds UI text. */
enum class Unavailable {
    /** The app may not read media sessions (notification access is off). */
    NO_MEDIA_ACCESS,

    /** CarPlay has no music session yet (nothing has played since it connected). */
    NO_CARPLAY_SESSION,

    /**
     * Needs Handlebar Link's own CarPlay: CarLink takes call keys only from a car's key events or a broadcast
     * that needs the system DUMP permission (CarLink `CarPlayCallKeys.kt:52`; docs/LESSONS.md 44), and Siri the
     * same way; and CarLink does not show a ringing call to other apps.
     */
    NEEDS_OUR_CARPLAY,

    /** Muting the microphone only works during a call (so it can never stay muted after one). */
    NO_CALL,

    /** Android did not change the microphone mute (read back after the change; [Microphone]). */
    MIC_REFUSED,
}

/** The outcome of a [ControlAction]. */
sealed interface ControlResult {
    data object Done : ControlResult

    data class NotDone(
        val reason: Unavailable,
    ) : ControlResult
}

/** Where actions go. Today: CarLink's CarPlay media session; later our own CarPlay engine. */
interface ControlTarget {
    /** The actions this target can ever perform; the others always come back [Unavailable.NEEDS_OUR_CARPLAY]. */
    val supported: Set<ControlAction>

    /** Performs [action]; never throws. */
    fun perform(action: ControlAction): ControlResult
}

/** The outcome of a [RiderAction], for the banner. */
sealed interface ActionOutcome {
    /** Done; [note] adds its result for the banner ("70%", what was said), null when there is nothing to add. */
    data class Done(
        val note: String? = null,
    ) : ActionOutcome

    /** Not done, because of [reason]. */
    data class NotDone(
        val reason: Unavailable,
    ) : ActionOutcome
}

/** Does a [RiderAction] (the app's [RiderActions]; a fake in tests). */
fun interface ActionPerformer {
    /** Does [action]; never throws. */
    suspend fun perform(action: RiderAction): ActionOutcome
}

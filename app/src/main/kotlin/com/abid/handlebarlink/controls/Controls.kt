package com.abid.handlebarlink.controls

/**
 * What a handlebar press asks CarPlay to do (docs/research/01-architecture.md §2.4). Our own CarPlay
 * ([OurCarPlayControlTarget]) sends the music keys as media HID keys, [ANSWER_CALL] as the telephony HID hook switch,
 * [DECLINE_CALL] and [END_CALL] as the telephony HID drop (DiPlay `CarPlayController.kt:750-753`), and [SIRI] as the
 * AirPlay command `requestSiri` (siriAction 2, then 3; DiPlay `AirPlaySession.kt:390-393`).
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
    /** Our CarPlay is not connected to the iPhone, so there is nothing to send the press to. */
    NO_CARPLAY_SESSION,

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

/** Where actions go: our own CarPlay ([OurCarPlayControlTarget]; a fake in tests). */
interface ControlTarget {
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

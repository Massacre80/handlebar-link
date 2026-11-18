package com.abid.handlebarlink.ui.handlebar

import androidx.compose.runtime.Immutable
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.ActionAvailability
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.ui.controls.ActionChoice
import com.abid.handlebarlink.ui.controls.handlebarChoices

/** What the Handlebar page says above the switch about the chosen situation and today's CarPlay. */
enum class HandlebarNotice {
    /** Ringing: CarLink does not show a ringing call, so this layer waits for our own CarPlay. */
    RINGING_NEEDS_OUR_CARPLAY,

    /** On a call: mute and the call's volume work today; hanging up waits for our own CarPlay. */
    HANG_UP_NEEDS_OUR_CARPLAY,
}

/**
 * The Handlebar page (Paper "Handlebar — Smart buttons"): the [situation] being shown and, for it, what every
 * press does with whether it works today ([presses], every [HandlebarSwitch]); the [notice] for the situation,
 * and whether the situation's layer is still at its defaults ([atDefaults]: the line under the switch then
 * describes them).
 */
@Immutable
data class HandlebarUiState(
    val situation: CallSituation,
    val presses: Map<HandlebarSwitch, ActionChoice>,
    val notice: HandlebarNotice? = null,
    val atDefaults: Boolean = true,
) {
    /** What [switch] does here. */
    fun press(switch: HandlebarSwitch): ActionChoice = presses[switch] ?: ActionChoice(RiderAction.NOTHING)
}

/** The page for [situation] from the rider's [mapping] and what works with today's CarPlay ([availability]). */
fun handlebarState(
    situation: CallSituation,
    mapping: Map<CallSituation, Map<HandlebarSwitch, RiderAction>>,
    availability: ActionAvailability,
): HandlebarUiState {
    val layer = mapping[situation] ?: HANDLEBAR_DEFAULTS.getValue(situation)
    val notice =
        when {
            situation == CallSituation.RINGING && availability.whyUnavailable(situation) != null -> {
                HandlebarNotice.RINGING_NEEDS_OUR_CARPLAY
            }

            situation == CallSituation.ON_CALL && availability.whyUnavailable(RiderAction.END_CALL) != null -> {
                HandlebarNotice.HANG_UP_NEEDS_OUR_CARPLAY
            }

            else -> {
                null
            }
        }
    return HandlebarUiState(
        situation = situation,
        presses =
            HandlebarSwitch.entries.associateWith { switch ->
                val action = layer[switch] ?: RiderAction.NOTHING
                ActionChoice(action, availability.whyUnavailable(action))
            },
        notice = notice,
        atDefaults = layer == HANDLEBAR_DEFAULTS.getValue(situation),
    )
}

/** The chooser for [switch] in [situation]: the press's action now and the situation's choices. */
@Immutable
data class PickerUiState(
    val situation: CallSituation,
    val switch: HandlebarSwitch,
    val chosen: RiderAction,
    val choices: List<ActionChoice>,
)

/** The chooser for [switch] in [situation], from the rider's [mapping]. */
fun pickerState(
    situation: CallSituation,
    switch: HandlebarSwitch,
    mapping: Map<CallSituation, Map<HandlebarSwitch, RiderAction>>,
    availability: ActionAvailability,
): PickerUiState =
    PickerUiState(
        situation = situation,
        switch = switch,
        chosen = mapping[situation]?.get(switch) ?: RiderAction.NOTHING,
        choices = handlebarChoices(situation, availability),
    )

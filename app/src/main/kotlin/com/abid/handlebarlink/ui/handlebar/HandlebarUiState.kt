package com.abid.handlebarlink.ui.handlebar

import androidx.compose.runtime.Immutable
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.ActionAvailability
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.ui.controls.ActionChoice
import com.abid.handlebarlink.ui.controls.handlebarChoices

/**
 * The Handlebar page (Paper "Handlebar — Smart buttons"): the [situation] being shown and, for it, what every
 * press does with whether it can work ([presses], every [HandlebarSwitch]), and whether the situation's layer is
 * still at its defaults ([atDefaults]: the line under the switch then describes them). With our own CarPlay every
 * situation and action works, so the page has no "needs our CarPlay" notices or "Soon" tags any more (owner,
 * 2026-11-20: our CarPlay replaced CarLink).
 */
@Immutable
data class HandlebarUiState(
    val situation: CallSituation,
    val presses: Map<HandlebarSwitch, ActionChoice>,
    val atDefaults: Boolean = true,
) {
    /** What [switch] does here. */
    fun press(switch: HandlebarSwitch): ActionChoice = presses[switch] ?: ActionChoice(RiderAction.NOTHING)
}

/** The page for [situation] from the rider's [mapping] and what can never work on this phone ([availability]). */
fun handlebarState(
    situation: CallSituation,
    mapping: Map<CallSituation, Map<HandlebarSwitch, RiderAction>>,
    availability: ActionAvailability,
): HandlebarUiState {
    val layer = mapping[situation] ?: HANDLEBAR_DEFAULTS.getValue(situation)
    return HandlebarUiState(
        situation = situation,
        presses =
            HandlebarSwitch.entries.associateWith { switch ->
                val action = layer[switch] ?: RiderAction.NOTHING
                ActionChoice(action, availability.whyUnavailable(action))
            },
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

package com.abid.handlebarlink.ui.handlebar

import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.controls.allowedActions
import com.abid.handlebarlink.ui.controls.ActionChoice

// The Handlebar page's sample states for previews and screenshot tests (no Hilt graph).

/**
 * The page in [situation], for previews and screenshot tests (every action works with our own CarPlay); riding has a
 * remapped up press next to the free left press.
 */
fun previewHandlebar(situation: CallSituation): HandlebarUiState {
    val layer = HANDLEBAR_DEFAULTS.getValue(situation)
    val changed = if (situation == CallSituation.RIDING) layer + (HandlebarSwitch.UP to RiderAction.BATTERY) else layer
    return HandlebarUiState(
        situation = situation,
        presses = changed.mapValues { (_, action) -> ActionChoice(action) },
        atDefaults = changed == layer,
    )
}

/** The chooser for hold left on a call (Paper "Handlebar 5"). */
fun previewPicker(
    situation: CallSituation = CallSituation.ON_CALL,
    switch: HandlebarSwitch = HandlebarSwitch.HOLD_LEFT,
): PickerUiState =
    PickerUiState(
        situation,
        switch,
        HANDLEBAR_DEFAULTS.getValue(situation).getValue(switch),
        allowedActions(situation).map { ActionChoice(it) },
    )

package com.abid.handlebarlink.ui.handlebar

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.ui.controls.RiderActionSheet

/**
 * The chooser for one press in one situation (Paper "Handlebar 5 — Picker"): the press in brand, the situation
 * and the key it is on, the situation's choices, and why music keys are missing during calls.
 */
@Composable
fun HandlebarPicker(
    state: PickerUiState,
    onChoose: (RiderAction) -> Unit,
    onCancel: () -> Unit,
) {
    val key = SwitchKey.of(state.switch)
    RiderActionSheet(
        key.icon,
        stringResource(pressName(state.switch)),
        stringResource(
            R.string.handlebar_sheet_subtitle,
            stringResource(situationName(state.situation)),
            stringResource(keyName(key)),
        ),
        state.chosen,
        onChoose = onChoose,
        onCancel = onCancel,
        choices = state.choices,
        situation = state.situation,
        note = stringResource(pickerNote(state.situation)),
        brandHeader = true,
    )
}

/** A key as the rider knows it ("the phone key"). */
@StringRes
private fun keyName(key: SwitchKey): Int =
    when (key) {
        SwitchKey.UP -> R.string.handlebar_key_up_name
        SwitchKey.RIGHT -> R.string.handlebar_key_right_name
        SwitchKey.DOWN -> R.string.handlebar_key_down_name
        SwitchKey.LEFT -> R.string.handlebar_key_left_name
    }

/** Why some actions are not offered in a situation. */
@StringRes
private fun pickerNote(situation: CallSituation): Int =
    when (situation) {
        CallSituation.RIDING -> R.string.handlebar_picker_note_riding
        CallSituation.RINGING -> R.string.handlebar_picker_note_ringing
        CallSituation.ON_CALL -> R.string.handlebar_picker_note_on_call
    }

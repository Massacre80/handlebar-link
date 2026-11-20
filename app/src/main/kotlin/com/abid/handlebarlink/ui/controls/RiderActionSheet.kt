package com.abid.handlebarlink.ui.controls

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.controls.Unavailable
import com.abid.handlebarlink.core.designsystem.component.HlHeader
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlSheet
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * Choosing what a button does (a handlebar press, the intercom's assistant button), Paper "Handlebar 5": the
 * header (the key in brand when [brandHeader]), the [choices] under their headings ([groupedChoices]) with a line
 * on what each does in [situation], "Nothing" on its own at the end, then [note] (why some actions are not
 * offered). The chosen one is marked in brand; one that needs our own CarPlay carries a "Soon" tag; another
 * reason it cannot work replaces its line.
 */
@Composable
fun RiderActionSheet(
    icon: Int,
    title: String,
    detail: String,
    chosen: RiderAction,
    onChoose: (RiderAction) -> Unit,
    onCancel: () -> Unit,
    choices: List<ActionChoice> = ASSISTANT_CHOICES,
    situation: CallSituation? = null,
    note: String? = null,
    brandHeader: Boolean = false,
) {
    val spacing = HlTheme.spacing
    HlSheet(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
        ) {
            HlHeader(icon, title, detail, brand = brandHeader)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s20)) {
                groupedChoices(choices).forEach { (group, inGroup) ->
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                        HlText(
                            stringResource(group.label).uppercase(),
                            HlTheme.typography.overline.copy(fontWeight = FontWeight.SemiBold),
                            HlTheme.colors.inkMuted,
                            Modifier.padding(horizontal = spacing.s4).semantics { heading() },
                        )
                        inGroup.forEach { ChoiceRow(it, chosen, situation, onChoose) }
                    }
                }
                choices
                    .firstOrNull {
                        actionGroup(
                            it.action,
                        ) == null
                    }?.let { ChoiceRow(it, chosen, situation, onChoose) }
            }
            note?.let {
                HlText(
                    it,
                    HlTheme.typography.label.copy(fontWeight = FontWeight.Normal),
                    HlTheme.colors.inkMuted,
                    Modifier.padding(horizontal = spacing.s4),
                )
            }
            HlSecondaryButton(stringResource(R.string.cancel), onCancel)
        }
    }
}

@Composable
private fun ChoiceRow(
    choice: ActionChoice,
    chosen: RiderAction,
    situation: CallSituation?,
    onChoose: (RiderAction) -> Unit,
) {
    val action = choice.action
    val reason = choice.unavailable
    HlOptionRow(
        actionIcon(action),
        stringResource(actionName(action)),
        // A reason it can never work (the microphone Android does not let the app change) replaces the line.
        if (reason == null) {
            stringResource(actionDetail(action, situation))
        } else {
            stringResource(unavailableText(reason))
        },
        selected = chosen == action,
        onClick = { onChoose(action) },
        enabled = choice.choosable,
        destructive = isDestructive(action),
        accent = true,
    )
}

/** An action's icon (what it does, not the key it is on). */
@DrawableRes
fun actionIcon(action: RiderAction): Int =
    when (action) {
        RiderAction.PLAY_PAUSE -> HlIcons.PlayPause
        RiderAction.NEXT -> HlIcons.Next
        RiderAction.PREVIOUS -> HlIcons.Previous
        RiderAction.VOLUME_UP -> HlIcons.VolumeUp
        RiderAction.VOLUME_DOWN -> HlIcons.VolumeDown
        RiderAction.MUTE -> HlIcons.Mute
        RiderAction.BRIGHTNESS_UP -> HlIcons.BrightnessUp
        RiderAction.BRIGHTNESS_DOWN -> HlIcons.BrightnessDown
        RiderAction.SWITCH_SCREEN -> HlIcons.SwitchScreen
        RiderAction.OPEN_CARPLAY -> HlIcons.CarPlay
        RiderAction.OPEN_APP -> HlIcons.Home
        RiderAction.BATTERY -> HlIcons.Battery
        RiderAction.TIME -> HlIcons.Time
        RiderAction.ANSWER_CALL -> HlIcons.Call
        RiderAction.DECLINE_CALL -> HlIcons.HangUp
        RiderAction.END_CALL -> HlIcons.HangUp
        RiderAction.MUTE_MIC -> HlIcons.MicOff
        RiderAction.SIRI -> HlIcons.Voice
        RiderAction.NOTHING -> HlIcons.None
    }

/** An action's name. */
@StringRes
fun actionName(action: RiderAction): Int =
    when (action) {
        RiderAction.PLAY_PAUSE -> R.string.assistant_play_pause
        RiderAction.NEXT -> R.string.assistant_next
        RiderAction.PREVIOUS -> R.string.assistant_previous
        RiderAction.VOLUME_UP -> R.string.action_volume_up
        RiderAction.VOLUME_DOWN -> R.string.action_volume_down
        RiderAction.MUTE -> R.string.action_mute
        RiderAction.BRIGHTNESS_UP -> R.string.action_brightness_up
        RiderAction.BRIGHTNESS_DOWN -> R.string.action_brightness_down
        RiderAction.SWITCH_SCREEN -> R.string.action_switch_screen
        RiderAction.OPEN_CARPLAY -> R.string.action_open_carplay
        RiderAction.OPEN_APP -> R.string.action_open_app
        RiderAction.BATTERY -> R.string.assistant_battery
        RiderAction.TIME -> R.string.action_time
        RiderAction.ANSWER_CALL -> R.string.action_answer_call
        RiderAction.DECLINE_CALL -> R.string.action_decline_call
        RiderAction.END_CALL -> R.string.action_end_call
        RiderAction.MUTE_MIC -> R.string.action_mute_mic
        RiderAction.SIRI -> R.string.assistant_siri
        RiderAction.NOTHING -> R.string.assistant_nothing
    }

/**
 * What [action] does, said for [situation] where it differs there (the volume keys change the call's volume
 * during calls).
 */
@StringRes
fun actionDetail(
    action: RiderAction,
    situation: CallSituation? = null,
): Int {
    val call = situation == CallSituation.RINGING || situation == CallSituation.ON_CALL
    return when {
        call && action == RiderAction.VOLUME_UP -> R.string.action_volume_up_call_detail
        call && action == RiderAction.VOLUME_DOWN -> R.string.action_volume_down_call_detail
        else -> actionDetail(action)
    }
}

@StringRes
private fun actionDetail(action: RiderAction): Int =
    when (action) {
        RiderAction.PLAY_PAUSE -> R.string.assistant_play_pause_detail
        RiderAction.NEXT -> R.string.assistant_next_detail
        RiderAction.PREVIOUS -> R.string.assistant_previous_detail
        RiderAction.VOLUME_UP -> R.string.action_volume_up_detail
        RiderAction.VOLUME_DOWN -> R.string.action_volume_down_detail
        RiderAction.MUTE -> R.string.action_mute_detail
        RiderAction.BRIGHTNESS_UP -> R.string.action_brightness_up_detail
        RiderAction.BRIGHTNESS_DOWN -> R.string.action_brightness_down_detail
        RiderAction.SWITCH_SCREEN -> R.string.action_switch_screen_detail
        RiderAction.OPEN_CARPLAY -> R.string.action_open_carplay_detail
        RiderAction.OPEN_APP -> R.string.action_open_app_detail
        RiderAction.BATTERY -> R.string.assistant_battery_detail
        RiderAction.TIME -> R.string.action_time_detail
        RiderAction.ANSWER_CALL -> R.string.action_answer_call_detail
        RiderAction.DECLINE_CALL -> R.string.action_decline_call_detail
        RiderAction.END_CALL -> R.string.action_end_call_detail
        RiderAction.MUTE_MIC -> R.string.action_mute_mic_detail
        RiderAction.SIRI -> R.string.assistant_siri_detail
        RiderAction.NOTHING -> R.string.assistant_nothing_detail
    }

/** Why an action was not done, for the banner and the choices. */
@StringRes
fun unavailableText(reason: Unavailable): Int =
    when (reason) {
        Unavailable.NO_CARPLAY_SESSION -> R.string.unavailable_no_carplay_session
        Unavailable.NO_CALL -> R.string.unavailable_no_call
        Unavailable.MIC_REFUSED -> R.string.unavailable_mic_refused
    }

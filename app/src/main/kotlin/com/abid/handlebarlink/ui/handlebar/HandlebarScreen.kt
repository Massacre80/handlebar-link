package com.abid.handlebarlink.ui.handlebar

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlSectionLabel
import com.abid.handlebarlink.core.designsystem.component.HlSegment
import com.abid.handlebarlink.core.designsystem.component.HlSegmentedControl
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.controls.ActionChoice
import com.abid.handlebarlink.ui.controls.actionName
import com.abid.handlebarlink.ui.controls.isDestructive
import com.abid.handlebarlink.ui.controls.unavailableText
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.settings.SubPage
import com.abid.handlebarlink.ui.settings.SubPagePanes

/**
 * Handlebar controls with smart buttons (Paper "Handlebar — Smart buttons"; like the earbuds apps' control
 * pages): the situation switch (riding, ringing, on a call) with a line on it; the switch with what its keys do in
 * that situation ([SwitchDiagram]); one block per key, its icon as on the switch, with its press and its hold, each
 * opening the chooser (a hold the meter keeps is dimmed); Reset to default ([onReset] only asks: the confirmation
 * dialog resets). Our own CarPlay does every action, so nothing is marked "Soon" (owner, 2026-11-20).
 */
@Composable
fun HandlebarScreen(
    state: HandlebarUiState,
    onSituation: (CallSituation) -> Unit,
    onPress: (HandlebarSwitch) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val callouts = remember(state) { keyCallouts(state.situation) { state.press(it).action } }
    if (LocalWindowLayout.current == WindowLayout.PANES) {
        // On its side: the switch beside its settings, as large as the pane's height allows (it was the column's
        // full width, 570 dp, and the 1024 px photo was blown up past its pixels), the rest scrolling next to it.
        SubPagePanes(
            stringResource(R.string.handlebar_title),
            onBack,
            start = {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    SwitchDiagram(callouts, Modifier.size(minOf(maxWidth, maxHeight)))
                }
            },
        ) {
            SituationBlock(state, onSituation)
            SwitchKey.entries.forEach { key -> KeyBlock(key, state, onPress) }
            HlSecondaryButton(stringResource(R.string.handlebar_reset), onReset, Modifier.fillMaxWidth())
        }
        return
    }
    SubPage(stringResource(R.string.handlebar_title), onBack) {
        SituationBlock(state, onSituation)
        SwitchDiagram(callouts, Modifier.fillMaxWidth())
        SwitchKey.entries.forEach { key -> KeyBlock(key, state, onPress) }
        HlSecondaryButton(stringResource(R.string.handlebar_reset), onReset, Modifier.fillMaxWidth())
    }
}

/** The situation switch and a line on the situation shown. */
@Composable
private fun SituationBlock(
    state: HandlebarUiState,
    onSituation: (CallSituation) -> Unit,
) {
    val spacing = HlTheme.spacing
    val segments =
        SITUATIONS.map { (_, icon, label) -> HlSegment(icon, stringResource(label)) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s10)) {
        HlSegmentedControl(
            segments,
            selected = SITUATIONS.indexOfFirst { it.situation == state.situation },
            onSelect = { onSituation(SITUATIONS[it].situation) },
        )
        Note(stringResource(situationHint(state.situation, state.atDefaults)))
    }
}

/** A key: its icon (dark, as the key looks) and direction, then Press and Press and hold, then a footnote. */
@Composable
private fun KeyBlock(
    key: SwitchKey,
    state: HandlebarUiState,
    onPress: (HandlebarSwitch) -> Unit,
) {
    val spacing = HlTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s8)) {
            KeyIcon(key, Modifier.padding(start = spacing.s4))
            HlSectionLabel(stringResource(key.label))
        }
        HlGroup {
            ActionRow(R.string.handlebar_press, key.press, state.press(key.press), onPress)
            HlGroupDivider()
            val hold = key.hold
            if (hold != null) {
                ActionRow(R.string.handlebar_hold, hold, state.press(hold), onPress)
            } else {
                HlValueRow(
                    stringResource(R.string.handlebar_hold),
                    hint = stringResource(R.string.handlebar_kept),
                    modifier = Modifier.alpha(KEPT_ALPHA),
                    container = Color.Transparent,
                )
            }
        }
        // Why a press here can never work (the microphone Android does not let the app change).
        val reason = listOfNotNull(key.press, key.hold).firstNotNullOfOrNull { state.press(it).unavailable }
        reason?.let { Note(stringResource(unavailableText(it))) }
    }
}

@Composable
private fun ActionRow(
    @StringRes gesture: Int,
    switch: HandlebarSwitch,
    choice: ActionChoice,
    onPress: (HandlebarSwitch) -> Unit,
) {
    HlValueRow(
        stringResource(gesture),
        hint = stringResource(actionName(choice.action)),
        onClick = { onPress(switch) },
        container = Color.Transparent,
        chevron = true,
        hintColor = if (isDestructive(choice.action)) HlTheme.colors.danger else Color.Unspecified,
    )
}

/** A muted line under a block, set in from the edge as the design's hints are. */
@Composable
private fun Note(text: String) {
    HlText(
        text,
        HlTheme.typography.label.copy(fontWeight = FontWeight.Normal),
        HlTheme.colors.inkMuted,
        Modifier.padding(horizontal = HlTheme.spacing.s6),
    )
}

/** A key as it looks on the switch: its icon on a dark rounded key, in every look. */
@Composable
internal fun KeyIcon(
    key: SwitchKey,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    Box(
        modifier
            .size(HlTheme.sizes.iconMedium + HlTheme.spacing.s8)
            .clip(RoundedCornerShape(HlTheme.radii.avatar))
            .background(colors.surfaceInverse),
        contentAlignment = Alignment.Center,
    ) {
        HlIcon(key.icon, colors.inkInverse, size = HlTheme.sizes.iconMicro)
    }
}

/** A situation on the switch: its icon and name. */
internal data class SituationTab(
    val situation: CallSituation,
    val icon: Int,
    @param:StringRes val label: Int,
)

/** The situations, in the switch's order. */
internal val SITUATIONS =
    listOf(
        SituationTab(CallSituation.RIDING, HlIcons.Bike, R.string.handlebar_situation_riding),
        SituationTab(CallSituation.RINGING, HlIcons.Bell, R.string.handlebar_situation_ringing),
        SituationTab(CallSituation.ON_CALL, HlIcons.Handset, R.string.handlebar_situation_on_call),
    )

/** A situation's name ("On a call"). */
@StringRes
internal fun situationName(situation: CallSituation): Int = SITUATIONS.first { it.situation == situation }.label

/**
 * The line under the switch: what the situation does by default; once the rider changed its layer, only what
 * it is (the rows show the rest).
 */
@StringRes
private fun situationHint(
    situation: CallSituation,
    atDefaults: Boolean,
): Int =
    when {
        situation == CallSituation.RIDING -> R.string.handlebar_hint_riding
        situation == CallSituation.RINGING && atDefaults -> R.string.handlebar_hint_ringing
        situation == CallSituation.RINGING -> R.string.handlebar_hint_ringing_set
        atDefaults -> R.string.handlebar_hint_on_call
        else -> R.string.handlebar_hint_on_call_set
    }

/** A press in full, for the chooser's title ("Press up", "Hold down"). */
@StringRes
fun pressName(switch: HandlebarSwitch): Int =
    when (switch) {
        HandlebarSwitch.UP -> R.string.handlebar_press_up
        HandlebarSwitch.DOWN -> R.string.handlebar_press_down
        HandlebarSwitch.LEFT -> R.string.handlebar_press_left
        HandlebarSwitch.RIGHT -> R.string.handlebar_press_right
        HandlebarSwitch.HOLD_DOWN -> R.string.handlebar_hold_down
        HandlebarSwitch.HOLD_LEFT -> R.string.handlebar_hold_left
    }

private const val KEPT_ALPHA = 0.45f

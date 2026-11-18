package com.abid.handlebarlink.ui.handlebar

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.component.HlCard
import com.abid.handlebarlink.core.designsystem.component.HlDirectionPad
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlPadLabel
import com.abid.handlebarlink.core.designsystem.component.HlQuietButton
import com.abid.handlebarlink.core.designsystem.component.HlSection
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.controls.actionName
import com.abid.handlebarlink.ui.navigation.containerContent
import com.abid.handlebarlink.ui.navigation.containerTransform
import com.abid.handlebarlink.ui.navigation.rememberContainerTransform
import com.abid.handlebarlink.ui.settings.SubPage

/**
 * Handlebar buttons (the earbuds apps' button screens): the switch as a picture with what each direction
 * does; then every press the phone receives, grouped by gesture, each opening the chooser; the two holds
 * the meter keeps, shown and dimmed; Reset to default.
 */
@Composable
fun HandlebarScreen(
    mapping: Map<HandlebarSwitch, RiderAction>,
    onPress: (HandlebarSwitch) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    SubPage(stringResource(R.string.handlebar_title), onBack) {
        HlCard(radius = HlTheme.radii.card) {
            HandlebarPad(mapping, Modifier.padding(HlTheme.spacing.s20))
        }
        HlSection(stringResource(R.string.handlebar_press)) {
            PRESSES.forEachIndexed { index, switch ->
                if (index > 0) HlGroupDivider()
                PressRow(switch, mapping, onPress)
            }
        }
        HlSection(stringResource(R.string.handlebar_hold)) {
            HOLDS.forEachIndexed { index, switch ->
                if (index > 0) HlGroupDivider()
                PressRow(switch, mapping, onPress)
            }
            KEPT.forEach { direction ->
                HlGroupDivider()
                // The meter keeps these; shown so the rider knows they exist.
                HlValueRow(
                    stringResource(direction),
                    hint = stringResource(R.string.handlebar_kept),
                    modifier = Modifier.alpha(KEPT_ALPHA),
                    container = Color.Transparent,
                )
            }
        }
        HlQuietButton(stringResource(R.string.handlebar_reset), onReset, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PressRow(
    switch: HandlebarSwitch,
    mapping: Map<HandlebarSwitch, RiderAction>,
    onPress: (HandlebarSwitch) -> Unit,
) {
    HlValueRow(
        stringResource(directionName(switch)),
        hint = stringResource(actionName(mapping[switch] ?: RiderAction.NOTHING)),
        onClick = { onPress(switch) },
        container = Color.Transparent,
        chevron = true,
    )
}

/** The four short presses as a picture, for the page and Home's card. */
@Composable
fun HandlebarPad(
    mapping: Map<HandlebarSwitch, RiderAction>,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun label(switch: HandlebarSwitch): HlPadLabel {
        val action = mapping[switch] ?: RiderAction.NOTHING
        return HlPadLabel(stringResource(actionName(action)), unset = action == RiderAction.NOTHING)
    }
    val up = label(HandlebarSwitch.UP)
    val down = label(HandlebarSwitch.DOWN)
    val left = label(HandlebarSwitch.LEFT)
    val right = label(HandlebarSwitch.RIGHT)
    HlDirectionPad(
        up,
        down,
        left,
        right,
        stringResource(R.string.handlebar_pad_description, up.text, down.text, left.text, right.text),
        modifier,
    )
}

/**
 * Home's Handlebar card: the pad with what each press does now, and the holds in a line; the card grows
 * into the Handlebar buttons page.
 */
@Composable
fun HandlebarCard(
    mapping: Map<HandlebarSwitch, RiderAction>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = HlTheme.spacing
    val grow = rememberContainerTransform(HANDLEBAR, expanded = false)
    HlCard(modifier.containerTransform(grow), radius = HlTheme.radii.card, onClick = onOpen) {
        Column(
            Modifier.containerContent(grow).padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HlText(
                    stringResource(R.string.handlebar_title),
                    HlTheme.typography.value,
                    HlTheme.colors.ink,
                    Modifier.weight(1f),
                )
                HlIcon(HlIcons.ChevronRight, HlTheme.colors.inkMuted, size = HlTheme.sizes.iconTiny)
            }
            HandlebarPad(mapping)
            HlText(
                stringResource(
                    R.string.handlebar_holds,
                    stringResource(actionName(mapping[HandlebarSwitch.HOLD_DOWN] ?: RiderAction.NOTHING)),
                    stringResource(actionName(mapping[HandlebarSwitch.HOLD_LEFT] ?: RiderAction.NOTHING)),
                ),
                HlTheme.typography.meta,
                HlTheme.colors.inkMuted,
            )
        }
    }
}

/** A press as the page lists it, under its gesture's section. */
@StringRes
fun directionName(switch: HandlebarSwitch): Int =
    when (switch) {
        HandlebarSwitch.UP -> R.string.handlebar_up
        HandlebarSwitch.DOWN, HandlebarSwitch.HOLD_DOWN -> R.string.handlebar_down
        HandlebarSwitch.LEFT, HandlebarSwitch.HOLD_LEFT -> R.string.handlebar_left
        HandlebarSwitch.RIGHT -> R.string.handlebar_right
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

private val PRESSES = listOf(HandlebarSwitch.UP, HandlebarSwitch.DOWN, HandlebarSwitch.LEFT, HandlebarSwitch.RIGHT)
private val HOLDS = listOf(HandlebarSwitch.HOLD_DOWN, HandlebarSwitch.HOLD_LEFT)

/** Hold up sends nothing and hold right stays with the meter (SAB_PROTOCOL.md "Verified"). */
private val KEPT = listOf(R.string.handlebar_up, R.string.handlebar_right)
private const val KEPT_ALPHA = 0.45f

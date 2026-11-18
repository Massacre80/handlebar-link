package com.abid.handlebarlink.ui.handlebar

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlQuietButton
import com.abid.handlebarlink.core.designsystem.component.HlSectionLabel
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.controls.actionName
import com.abid.handlebarlink.ui.settings.SubPage

/**
 * Handlebar controls (like the earbuds apps' control pages): the switch with each key's press called out
 * ([SwitchDiagram]); then one block per key, its icon as on the switch, with its press and its hold
 * together, each opening the chooser (a hold the meter keeps is shown dimmed); Reset to default.
 */
@Composable
fun HandlebarScreen(
    mapping: Map<HandlebarSwitch, RiderAction>,
    onPress: (HandlebarSwitch) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    SubPage(stringResource(R.string.handlebar_title), onBack) {
        SwitchDiagram(mapping, Modifier.fillMaxWidth())
        SwitchKey.entries.forEach { key -> KeyBlock(key, mapping, onPress) }
        HlQuietButton(stringResource(R.string.handlebar_reset), onReset, Modifier.fillMaxWidth())
    }
}

/** A key: its icon (dark, as the key looks) and direction, then Press and Press and hold. */
@Composable
private fun KeyBlock(
    key: SwitchKey,
    mapping: Map<HandlebarSwitch, RiderAction>,
    onPress: (HandlebarSwitch) -> Unit,
) {
    val spacing = HlTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s8)) {
            KeyIcon(key, Modifier.padding(start = spacing.s4))
            HlSectionLabel(stringResource(key.label))
        }
        HlGroup {
            ActionRow(R.string.handlebar_press, key.press, mapping, onPress)
            HlGroupDivider()
            val hold = key.hold
            if (hold != null) {
                ActionRow(R.string.handlebar_hold, hold, mapping, onPress)
            } else {
                HlValueRow(
                    stringResource(R.string.handlebar_hold),
                    hint = stringResource(R.string.handlebar_kept),
                    modifier = Modifier.alpha(KEPT_ALPHA),
                    container = Color.Transparent,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    @StringRes gesture: Int,
    switch: HandlebarSwitch,
    mapping: Map<HandlebarSwitch, RiderAction>,
    onPress: (HandlebarSwitch) -> Unit,
) {
    HlValueRow(
        stringResource(gesture),
        hint = stringResource(actionName(mapping[switch] ?: RiderAction.NOTHING)),
        onClick = { onPress(switch) },
        container = Color.Transparent,
        chevron = true,
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

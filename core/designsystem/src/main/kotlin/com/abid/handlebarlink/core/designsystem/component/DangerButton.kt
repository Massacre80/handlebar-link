package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * A destructive page action that only asks ("Forget this bike", Paper "Garage 2"): [HlSecondaryButton]'s pill and
 * height, tinted with the danger colour (owner, 2026-11-20: destructive actions are a red-tinted secondary pill that
 * opens a confirmation, Cancel and the action side by side). The fill and edge are the danger tag's (12 % and 28 %),
 * the [icon] and label in danger, so it reads as a removal before it is tapped.
 */
@Composable
public fun HlDangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = HlIcons.Trash,
) {
    val colors = HlTheme.colors
    val press = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .height(HlTheme.sizes.controlLarge)
            .clip(CircleShape)
            .background(colors.danger.copy(alpha = DANGER_FILL))
            .border(HlTheme.strokes.hairline, colors.danger.copy(alpha = DANGER_EDGE), CircleShape)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HlIcon(icon, colors.danger, size = HlTheme.sizes.iconTiny)
        HlText(label, HlTheme.typography.value.copy(fontWeight = FontWeight.Medium), colors.danger, maxLines = 1)
    }
}

/** The danger tag's fill and edge (Notices.kt: 12 % and 28 %). */
private const val DANGER_FILL = 0.12f
private const val DANGER_EDGE = 0.28f

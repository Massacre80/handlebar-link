package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * A grouped list: a section's rows in one card, split by [HlGroupDivider]s. Rows inside use a transparent
 * container. Made to read in direct sun (owner, 2026-11-17): Night, 10 % white with a 22 % white edge (the
 * design's 7 % glass without an edge vanished on the near-black ground); Bento, a white card with a grey
 * edge on the pale ground.
 */
@Composable
public fun HlGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HlTheme.colors
    val shape = RoundedCornerShape(HlTheme.radii.card)
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (night) colors.raisedLo else colors.panel)
            .border(HlTheme.strokes.hairline, if (night) colors.lineStrong else colors.line, shape),
    ) {
        // Rows inside are square: the group's clip rounds the outer corners only (owner, 2026-11-17).
        CompositionLocalProvider(LocalInGroup provides true) { content() }
    }
}

/** True inside an [HlGroup]: rows there draw square, the group rounds its own corners. */
internal val LocalInGroup = staticCompositionLocalOf { false }

/** A row's shape: square inside an [HlGroup], else rounded by [radius]. */
@Composable
internal fun rowShape(radius: Dp): Shape = if (LocalInGroup.current) RectangleShape else RoundedCornerShape(radius)

/**
 * The hairline between two rows of an [HlGroup], from edge to edge (owner, 2026-11-17). Leave it out next
 * to a chosen (filled) row: the fill already separates it.
 */
@Composable
public fun HlGroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(HlTheme.strokes.hairline)
            .background(HlTheme.colors.line),
    )
}

/** A titled section of a settings page: its label, then its [HlGroup]. */
@Composable
public fun HlSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        HlSectionLabel(label)
        HlGroup(content = content)
    }
}

/**
 * A row's action as a pill: the design's white "Allow" (the row itself is the button). The call to
 * action of a permission or problem row.
 */
@Composable
public fun HlActionPill(text: String) {
    Box(
        Modifier
            .height(HlTheme.sizes.chip)
            .background(HlTheme.colors.action, CircleShape)
            .padding(horizontal = HlTheme.spacing.s16),
        contentAlignment = Alignment.Center,
    ) {
        HlText(
            text,
            HlTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            HlTheme.colors.onAction,
            maxLines = 1,
        )
    }
}

/**
 * The white call-to-action pill as a button of its own ([HlActionPill] marks a row that is the button): a
 * card's one action, such as "Connect" on a device card. Pressing scales it like every control; disabled, it
 * stays white and its label dims (an action under way, "Connecting…").
 */
@Composable
public fun HlPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HlTheme.colors
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(press, HlTheme.motion)
            .height(HlTheme.sizes.chip)
            .clip(CircleShape)
            .background(colors.action)
            .clickable(press, LocalIndication.current, enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = HlTheme.spacing.s16),
        contentAlignment = Alignment.Center,
    ) {
        HlText(
            text,
            HlTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            if (enabled) colors.onAction else colors.onAction.copy(alpha = DIMMED),
            maxLines = 1,
        )
    }
}

/** A disabled pill's label. */
private const val DIMMED = 0.5f

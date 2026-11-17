package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
        content = content,
    )
}

/** The hairline between two rows of an [HlGroup], inset from its edges. */
@Composable
public fun HlGroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HlTheme.spacing.s16)
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

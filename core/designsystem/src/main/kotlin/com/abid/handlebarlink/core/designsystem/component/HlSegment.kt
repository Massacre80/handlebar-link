package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/** One choice of an [HlSegmentedControl]: its icon and label. */
@Immutable
public data class HlSegment(
    /** The segment's icon. */
    @param:DrawableRes val icon: Int,
    /** The segment's name. */
    val label: String,
)

/**
 * A few views of one page side by side, one chosen (Paper "Handlebar — Smart buttons": Riding, Ringing, On a
 * call): a pill track with the chosen segment filled in brand. Each segment is a tab 48 dp tall to touch; its
 * pill is drawn 4 dp inside, as in the design (a 40 dp pill in a 4 dp padded track). Labels wrap with large
 * text instead of being cut.
 */
@Composable
public fun HlSegmentedControl(
    segments: List<HlSegment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.panel)
            .border(HlTheme.strokes.hairline, colors.line, CircleShape)
            .padding(horizontal = spacing.s4)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            Segment(segment, index == selected, { onSelect(index) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Segment(
    segment: HlSegment,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val ink = if (selected) colors.brandInk else colors.ink
    val press = remember { MutableInteractionSource() }
    // The whole 48 dp slot takes the touch; the press feedback and the fill belong to the pill inside it.
    Box(
        modifier
            .heightIn(min = HlTheme.sizes.touchTarget)
            .selectable(selected, press, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .pressScale(press, HlTheme.motion)
                .padding(vertical = spacing.s4)
                .fillMaxWidth()
                .heightIn(min = HlTheme.sizes.touchTarget - spacing.s8)
                .clip(CircleShape)
                .background(if (selected) colors.brand else Color.Transparent)
                .indication(press, LocalIndication.current)
                .padding(horizontal = spacing.s8, vertical = spacing.s4),
            horizontalArrangement = Arrangement.spacedBy(spacing.s8, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HlIcon(segment.icon, ink, size = HlTheme.sizes.iconMicro + spacing.s2)
            HlText(
                segment.label,
                HlTheme.typography.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                ink,
                Modifier.weight(1f, fill = false),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import kotlin.math.roundToInt

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
 * call): a pill track with the chosen segment filled in brand. Each segment is a tab 48 dp tall to touch; the
 * pill is drawn 4 dp inside, as in the design (a 40 dp pill in a 4 dp padded track). It is one pill that springs
 * to the chosen segment like the tab bar's chip while the labels recolour (owner, 2026-11-20). Labels wrap with
 * large text instead of being cut.
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
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.panel)
            .border(HlTheme.strokes.hairline, colors.line, CircleShape),
    ) {
        val count = segments.size.coerceAtLeast(1)
        val slot = (maxWidth - spacing.s4 * 2 - spacing.s4 * (count - 1)) / count
        val target = with(LocalDensity.current) { (spacing.s4 + (slot + spacing.s4) * selected).toPx() }
        val pillX by animateFloatAsState(target, HlTheme.motion.layout(), label = "segment pill")
        Pill({ pillX }, slot, Modifier.matchParentSize())
        val presses = remember(count) { List(count) { MutableInteractionSource() } }
        val slotPx = with(LocalDensity.current) { slot.toPx() }
        // The labels in the track's ink, taking the touch.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.s4)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEachIndexed { index, segment ->
                Segment(segment, index == selected, colors.ink, presses[index], Modifier.weight(1f)) { onSelect(index) }
            }
        }
        // The same labels in the pill's ink, shown only where the pill is: mid-slide each label is dark exactly where
        // the lime covers it, never dark on the dark track.
        Row(
            Modifier
                .matchParentSize()
                .padding(horizontal = spacing.s4)
                .clearAndSetSemantics {}
                .drawWithContent {
                    val left = pillX - spacing.s4.toPx()
                    clipRect(left = left, right = left + slotPx) { this@drawWithContent.drawContent() }
                },
            horizontalArrangement = Arrangement.spacedBy(spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEachIndexed { index, segment ->
                Segment(segment, index == selected, colors.brandInk, presses[index], Modifier.weight(1f), null)
            }
        }
    }
}

/** The one pill at [x], [width] wide, as tall as the row (labels may wrap), 4 dp inside it. */
@Composable
private fun Pill(
    x: () -> Float,
    width: Dp,
    modifier: Modifier,
) {
    Box(modifier.padding(vertical = HlTheme.spacing.s4)) {
        Box(
            Modifier
                .offset { IntOffset(x().roundToInt(), 0) }
                .width(width)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(HlTheme.colors.brand),
        )
    }
}

/** One segment's icon and label in [ink]; with [onClick] it takes the touch (the masked copy has none). */
@Composable
private fun Segment(
    segment: HlSegment,
    selected: Boolean,
    ink: Color,
    press: MutableInteractionSource,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    val spacing = HlTheme.spacing
    // The whole 48 dp slot takes the touch; the press feedback is drawn on the pill's area inside it.
    Box(
        modifier
            .heightIn(min = HlTheme.sizes.touchTarget)
            .then(
                if (onClick != null) {
                    Modifier.selectable(selected, press, indication = null, role = Role.Tab, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .pressScale(press, HlTheme.motion)
                .padding(vertical = spacing.s4)
                .fillMaxWidth()
                .heightIn(min = HlTheme.sizes.touchTarget - spacing.s8)
                .clip(CircleShape)
                .then(if (onClick != null) Modifier.indication(press, LocalIndication.current) else Modifier)
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

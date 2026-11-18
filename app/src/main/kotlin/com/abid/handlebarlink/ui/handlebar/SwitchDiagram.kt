package com.abid.handlebarlink.ui.handlebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.controls.actionIcon
import com.abid.handlebarlink.ui.controls.actionName

/**
 * The switch with what each key does: the render, a dot on each key and a line from it to a callout in the
 * nearest corner naming the key's press (product-diagram style, like the earbuds apps' control pages).
 * Lines are worked out while placing the callouts and drawn between the render and the callouts.
 */
@Composable
internal fun SwitchDiagram(
    mapping: Map<HandlebarSwitch, RiderAction>,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val outline = HlTheme.strokes.outline
    val ends = remember { Array(SwitchKey.entries.size) { Offset.Zero } }
    val keys =
        SwitchKey.entries.map {
            stringResource(
                R.string.handlebar_key_description,
                stringResource(it.label),
                stringResource(actionName(mapping[it.press] ?: RiderAction.NOTHING)),
            )
        }
    val description = stringResource(R.string.handlebar_diagram_description, keys.joinToString(" "))
    Layout(
        content = { SwitchKey.entries.forEach { Callout(mapping[it.press] ?: RiderAction.NOTHING) } },
        modifier =
            modifier
                .clip(RoundedCornerShape(HlTheme.radii.card))
                .paint(painterResource(R.drawable.handlebar_switch), contentScale = ContentScale.Crop)
                .drawBehind {
                    val line = outline.toPx()
                    SwitchKey.entries.forEachIndexed { index, key ->
                        // From the key's edge (not its centre, which has the printed icon) to the callout.
                        val centre = Offset(key.spot.x * size.width, key.spot.y * size.height)
                        val toward = ends[index] - centre
                        val at = centre + toward / toward.getDistance() * (KEY_RADIUS * size.width)
                        drawLine(colors.brand, at, ends[index], strokeWidth = line)
                        drawCircle(colors.brand, radius = line * DOT, center = at)
                    }
                }.clearAndSetSemantics { contentDescription = description },
    ) { measurables, constraints ->
        val side = constraints.maxWidth
        val inset = spacing.s12.roundToPx()
        val loose = Constraints(maxWidth = side / 2 - inset, maxHeight = side)
        val placeables = measurables.map { it.measure(loose) }
        layout(side, side) {
            placeables.forEachIndexed { index, placeable ->
                val key = SwitchKey.entries[index]
                val x = if (key.spot.left) inset else side - inset - placeable.width
                val y = if (key.spot.top) inset else side - inset - placeable.height
                placeable.place(x, y)
                // The line ends on the callout's edge nearest the key.
                val target = Offset(key.spot.x * side, key.spot.y * side)
                ends[index] =
                    Offset(
                        target.x.coerceIn(x.toFloat(), (x + placeable.width).toFloat()),
                        target.y.coerceIn(y.toFloat(), (y + placeable.height).toFloat()),
                    )
            }
        }
    }
}

/** A key's callout: what its press does, as an icon and its name (dimmed when nothing). */
@Composable
private fun Callout(action: RiderAction) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val ink = if (action == RiderAction.NOTHING) colors.inkInverseMuted else colors.inkInverse
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.surfaceInverse)
            .padding(horizontal = spacing.s12, vertical = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        HlIcon(actionIcon(action), colors.brand, size = HlTheme.sizes.iconMicro)
        HlText(stringResource(actionName(action)), HlTheme.typography.label, ink, maxLines = 2)
    }
}

/** The dot on a key's edge, in line widths. */
private const val DOT = 2.5f

/** A key's half-width on the render, as a fraction of its width. */
private const val KEY_RADIUS = 0.07f

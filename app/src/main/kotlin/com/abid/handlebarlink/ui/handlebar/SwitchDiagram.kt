package com.abid.handlebarlink.ui.handlebar

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.controls.actionIcon
import com.abid.handlebarlink.ui.controls.actionName

/**
 * The switch with what its keys do: the render, a dot on each called-out key and a line from it to its callout
 * in the nearest corner (product-diagram style, like the earbuds apps' control pages). Lines are worked out while
 * placing the callouts and drawn between the render and the callouts.
 */
@Composable
internal fun SwitchDiagram(
    callouts: List<KeyCallout>,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val outline = HlTheme.strokes.outline
    val ends = remember(callouts.size) { Array(callouts.size) { Offset.Zero } }
    val keys =
        callouts.map {
            val press = stringResource(actionName(it.action))
            val hold = it.hold
            if (hold == null) {
                stringResource(R.string.handlebar_key_description, stringResource(it.key.label), press)
            } else {
                stringResource(
                    R.string.handlebar_key_hold_description,
                    stringResource(it.key.label),
                    press,
                    stringResource(actionName(hold)),
                )
            }
        }
    val description = stringResource(R.string.handlebar_diagram_description, keys.joinToString(" "))
    Layout(
        content = { callouts.forEach { Callout(it) } },
        modifier =
            modifier
                .clip(RoundedCornerShape(HlTheme.radii.card))
                .paint(painterResource(R.drawable.handlebar_switch), contentScale = ContentScale.Crop)
                .drawBehind {
                    val line = outline.toPx()
                    callouts.forEachIndexed { index, callout ->
                        // From the key's edge (not its centre, which has the printed icon) to the callout.
                        val spot = callout.key.spot
                        val centre = Offset(spot.x * size.width, spot.y * size.height)
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
                val spot = callouts[index].key.spot
                val x = if (spot.left) inset else side - inset - placeable.width
                val y = if (spot.top) inset else side - inset - placeable.height
                placeable.place(x, y)
                // The line ends on the callout's edge nearest the key.
                val target = Offset(spot.x * side, spot.y * side)
                ends[index] =
                    Offset(
                        target.x.coerceIn(x.toFloat(), (x + placeable.width).toFloat()),
                        target.y.coerceIn(y.toFloat(), (y + placeable.height).toFloat()),
                    )
            }
        }
    }
}

/** A key's callout: its press as an icon and a name (dimmed when nothing); the call key's in brand. */
@Composable
private fun Callout(callout: KeyCallout) {
    if (callout.call) {
        CallCallout(callout)
        return
    }
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val action = callout.action
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
        Column {
            HlText(stringResource(actionName(action)), HlTheme.typography.label, ink, maxLines = 2)
            callout.hold?.let { HlText(holdLine(it), HlTheme.typography.meta, colors.inkInverseMuted, maxLines = 2) }
        }
    }
}

/** The call key's chip (Paper "Handlebar 3/4"): brand, the press short ("Answer"), its hold below. */
@Composable
private fun CallCallout(callout: KeyCallout) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    Row(
        Modifier
            .clip(RoundedCornerShape(HlTheme.radii.control))
            .background(colors.brand)
            .padding(start = spacing.s12, end = spacing.s16, top = spacing.s8, bottom = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s10),
    ) {
        HlIcon(actionIcon(callout.action), colors.brandInk, size = HlTheme.sizes.iconMicro)
        Column {
            HlText(stringResource(calloutName(callout.action)), HlTheme.typography.value, colors.brandInk, maxLines = 2)
            callout.hold?.let {
                HlText(
                    holdLine(it),
                    HlTheme.typography.meta,
                    colors.brandInk.copy(alpha = ON_BRAND_MUTED),
                    maxLines = 2,
                )
            }
        }
    }
}

/** A press's name in a callout: the call actions short ("Answer", "Mute"), the rest as everywhere. */
@StringRes
private fun calloutName(action: RiderAction): Int =
    when (action) {
        RiderAction.ANSWER_CALL -> R.string.callout_answer
        RiderAction.DECLINE_CALL -> R.string.callout_decline
        RiderAction.END_CALL -> R.string.callout_hang_up
        RiderAction.MUTE_MIC -> R.string.callout_mute
        else -> actionName(action)
    }

/** A hold's line in a callout ("Hold to decline"; "Hold: Volume up" for the rest). */
@Composable
private fun holdLine(action: RiderAction): String =
    when (action) {
        RiderAction.ANSWER_CALL -> stringResource(R.string.callout_hold_answer)
        RiderAction.DECLINE_CALL -> stringResource(R.string.callout_hold_decline)
        RiderAction.END_CALL -> stringResource(R.string.callout_hold_hang_up)
        RiderAction.MUTE_MIC -> stringResource(R.string.callout_hold_mute)
        else -> stringResource(R.string.callout_hold, stringResource(actionName(action)))
    }

/** The dot on a key's edge, in line widths. */
private const val DOT = 2.5f

/** A key's half-width on the render, as a fraction of its width. */
private const val KEY_RADIUS = 0.07f

/** The hold line on the brand chip (Paper: brand ink at #0D0F0B9E). */
private const val ON_BRAND_MUTED = 0.62f

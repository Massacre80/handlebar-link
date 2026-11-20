package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * A due gauge (Paper "Garage 2", the next service card's "Gauge"): a half circle in three segments over a row of
 * ticks, lit from the left by [used] (0..1, null: the track only), the [value] ("102 days") and its [caption]
 * ("or 4,240 km") under the arch. [endDot] marks the end of the lit part (Paper "Garage 3": due soon), ringed in
 * [ground], the card it sits on. Drawn at the artboard's 196 x 112 dp.
 *
 * Its sizes are the artboard's, kept here until the design system has tokens for the gauge (they move with the
 * polish branch's tokens at the merge).
 */
@Composable
public fun HlDueArc(
    value: String,
    caption: String,
    used: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    endDot: Boolean = false,
    ground: Color = Color.Transparent,
) {
    val colors = HlTheme.colors
    Box(
        modifier
            .size(WIDTH_DP.dp, HEIGHT_DP.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$value, $caption" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxWidth().height(ARC_BOX_DP.dp).align(Alignment.TopCenter)) {
            val unit = size.width / WIDTH_DP
            val centre = Offset(size.width / 2, ARC_CENTRE_Y * unit)
            ticks(colors.lineStrong, centre, TICK_RADIUS * unit, TICK_WIDTH * unit)
            segments(colors.track, centre, ARC_RADIUS * unit, ARC_WIDTH * unit, 1f)
            used?.let { lit ->
                val fraction = lit.coerceIn(0f, 1f)
                segments(color, centre, ARC_RADIUS * unit, ARC_WIDTH * unit, fraction)
                if (endDot) {
                    val angle = Math.toRadians((HALF_TURN + HALF_TURN * fraction).toDouble())
                    val at = centre + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (ARC_RADIUS * unit)
                    drawCircle(ground, radius = (DOT_RADIUS + DOT_RING) * unit, center = at)
                    drawCircle(color, radius = DOT_RADIUS * unit, center = at)
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s2),
        ) {
            HlText(value, HlTheme.typography.figure, colors.ink, maxLines = 1)
            HlText(
                caption,
                HlTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                colors.inkMuted,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The arch's three segments (the SVG's `stroke-dasharray: 32 2 32 2 32` over a path length of 100), lit up to
 * [fraction] of the whole length, gaps included, as the design's progress dashes are.
 */
private fun DrawScope.segments(
    color: Color,
    centre: Offset,
    radius: Float,
    width: Float,
    fraction: Float,
) {
    val end = fraction * PATH_LENGTH
    repeat(SEGMENTS) { index ->
        val from = index * (SEGMENT + GAP)
        val to = minOf(from + SEGMENT, end)
        if (to > from) {
            drawArc(
                color,
                HALF_TURN + from / PATH_LENGTH * HALF_TURN,
                (to - from) / PATH_LENGTH * HALF_TURN,
                useCenter = false,
                topLeft = centre - Offset(radius, radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width),
            )
        }
    }
}

/** The ticks inside the arch: 15 short marks from 30° above the left to 30° above the right (`0.7 6.44`). */
private fun DrawScope.ticks(
    color: Color,
    centre: Offset,
    radius: Float,
    width: Float,
) {
    repeat(TICKS) { index ->
        val angle = TICK_START + index * TICK_STEP
        drawArc(
            color,
            angle - TICK_SWEEP / 2,
            TICK_SWEEP,
            useCenter = false,
            topLeft = centre - Offset(radius, radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width),
        )
    }
}

// The artboard's numbers (Paper "Garage 2", the gauge's SVG: 196 x 94 within a 112 dp box).
private const val WIDTH_DP = 196
private const val HEIGHT_DP = 112
private const val ARC_BOX_DP = 94
private const val ARC_CENTRE_Y = 92f
private const val ARC_RADIUS = 88f
private const val ARC_WIDTH = 4f
private const val TICK_RADIUS = 76f
private const val TICK_WIDTH = 5f
private const val DOT_RADIUS = 6f
private const val DOT_RING = 1.5f
private const val HALF_TURN = 180f
private const val PATH_LENGTH = 100f
private const val SEGMENTS = 3
private const val SEGMENT = 32f
private const val GAP = 2f
private const val TICKS = 15

/** The ticks' arc runs from 210° to 330° (screen angles), 0.7 % of its length each. */
private const val TICK_START = 210f
private const val TICK_STEP = 120f / (TICKS - 1)
private const val TICK_SWEEP = 120f * 0.007f

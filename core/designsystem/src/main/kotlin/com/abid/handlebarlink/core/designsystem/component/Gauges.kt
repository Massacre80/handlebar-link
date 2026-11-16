package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Night's open arc gauge (270°, gap at the bottom). [progress] 0..1, or null for an empty state (track
 * only). Shrinks below its design size when the screen is narrow.
 */
@Composable
public fun ArcGauge(
    value: String,
    caption: String,
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    overline: String? = null,
) {
    val colors = HlTheme.colors
    val type = HlTheme.typography
    val strokes = HlTheme.strokes
    val inset = HlTheme.sizes.gaugeInset
    Box(
        modifier
            .widthIn(max = HlTheme.sizes.gauge)
            .aspectRatio(1f)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    listOfNotNull(overline, value, caption).joinToString(", ")
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2 - inset.toPx()
            arc(colors.track, ARC_START, ARC_SWEEP, radius, strokes.track.toPx())
            progress?.let { arc(color, ARC_START, ARC_SWEEP * it.coerceIn(0f, 1f), radius, strokes.progress.toPx()) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            overline?.let { HlText(it, type.meta, colors.inkSoft, maxLines = 1) }
            HlText(value, type.figure, colors.ink, maxLines = 1, modifier = Modifier.padding(top = HlTheme.spacing.s6))
            HlText(caption, type.meta, colors.inkMuted, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

/** Bento's tick ring: 50 radial ticks, the first [progress] of them in the gauge colour; null: empty state. */
@Composable
public fun TickRing(
    value: String,
    caption: String,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val strokes = HlTheme.strokes
    Box(
        modifier
            .widthIn(max = HlTheme.sizes.ring)
            .aspectRatio(1f)
            .semantics(mergeDescendants = true) { contentDescription = "$value, $caption" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val length = strokes.ring.toPx()
            val radius = size.minDimension / 2 - length / 2 - RING_EDGE_PX
            ticks(RING_TICKS, radius, length, colors.track to colors.gauge, progress)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HlText(value, HlTheme.typography.figure, colors.ink, maxLines = 1)
            HlText(caption, HlTheme.typography.meta, colors.inkMuted, maxLines = 1)
        }
    }
}

/** A row of [count] ticks, the first [progress] of them filled; null: empty state. */
@Composable
public fun TickBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    count: Int = BAR_TICKS,
) {
    val colors = HlTheme.colors
    val filled = ((progress ?: 0f).coerceIn(0f, 1f) * count).roundToInt()
    Row(modifier.fillMaxWidth().height(HlTheme.sizes.tick), horizontalArrangement = Arrangement.SpaceBetween) {
        repeat(count) { index ->
            Canvas(Modifier.width(HlTheme.strokes.track).height(HlTheme.sizes.tick)) {
                drawRect(if (index < filled) colors.gauge else colors.track)
            }
        }
    }
}

/**
 * The CarPlay signal ring: 50 ticks around a tinted disc. [progress] null: the full ring in [color];
 * otherwise the first part in [color] over [track].
 */
@Composable
public fun SignalRing(
    color: Color,
    track: Color,
    fill: Color,
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = HlTheme.sizes.signal,
) {
    val tick = HlTheme.strokes.signal
    Canvas(modifier.size(size)) {
        val length = tick.toPx()
        val half = this.size.minDimension / 2
        drawCircle(fill, radius = half * SIGNAL_DISC_FRACTION)
        if (progress == null) {
            ticks(RING_TICKS, half * SIGNAL_RING_FRACTION, length, color to color, 1f)
        } else {
            ticks(RING_TICKS, half * SIGNAL_RING_FRACTION, length, track to color, progress)
        }
    }
}

private fun DrawScope.arc(
    color: Color,
    start: Float,
    sweep: Float,
    radius: Float,
    width: Float,
) {
    val topLeft = Offset(center.x - radius, center.y - radius)
    drawArc(
        color,
        start,
        sweep,
        false,
        topLeft,
        Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}

/** Radial ticks from the top, clockwise, like an SVG circle with `stroke-dasharray: 0.5 1.5` over 100. */
private fun DrawScope.ticks(
    count: Int,
    radius: Float,
    length: Float,
    trackAndFill: Pair<Color, Color>,
    progress: Float?,
) {
    val (track, color) = trackAndFill
    val filled = ((progress ?: 0f).coerceIn(0f, 1f) * count).roundToInt()
    val width = (2 * PI * radius / count * TICK_DUTY).toFloat()
    repeat(count) { index ->
        val angle = (index.toDouble() / count) * 2 * PI - PI / 2
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(
            if (index < filled) color else track,
            center + direction * (radius - length / 2),
            center + direction * (radius + length / 2),
            strokeWidth = width,
        )
    }
}

private const val ARC_START = 135f
private const val ARC_SWEEP = 270f
private const val RING_TICKS = 50
private const val BAR_TICKS = 20

/** Dash 0.5 of every 2 units (`stroke-dasharray: 0.5 1.5`). */
private const val TICK_DUTY = 0.25

/** The SVG ring sits 1 px inside the box edge (r 49 of 112 with a 12 px stroke). */
private const val RING_EDGE_PX = 1f

/** CarPlay ring in a 56 box (half 28): ticks on r 24, disc r 18. */
private const val SIGNAL_RING_FRACTION = 24f / 28f
private const val SIGNAL_DISC_FRACTION = 18f / 28f

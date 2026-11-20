package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.InstrumentType
import com.abid.handlebarlink.core.designsystem.theme.MeterGeometry
import com.abid.handlebarlink.core.designsystem.theme.centred
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The BikePlay meter (Paper "BikePlay card" design B "Instrument" and "BikePlay — flow" Tab 1–9): a value arc over a
// tick arc. Home's instrument card and the BikePlay tab's hero draw it.

/** The meter's colour: lime for good or progress, danger when the rider must act or a retry counts down. */
public enum class MeterTone {
    /** Running or starting (the brand lime). */
    GOOD,

    /** Needs the rider, or failed and retrying (danger). */
    DANGER,
}

/** What the meter's arc shows. */
@Immutable
public sealed interface MeterValue {
    /** Nothing lit: off or not set up (the dim arc). */
    public data object Empty : MeterValue

    /** The whole arc in the tone (Needs you). */
    public data object Full : MeterValue

    /** [lit] of [count] segments (Connected's link quality: Strong 3, Fair 2, Weak 1). */
    public data class Segments(
        /** The segments lit from the left. */
        val lit: Int,
        /** All segments. */
        val count: Int = SEGMENTS,
    ) : MeterValue

    /**
     * Lit from the left to [fraction] (0..1) with a knob at its end: a step of a start, or ([draining]) the part of a
     * retry's pause still to wait, which moves smoothly between the once-a-second updates.
     */
    public data class Progress(
        /** The lit part, 0..1. */
        val fraction: Float,
        /** A countdown: moves linearly between updates. */
        val draining: Boolean = false,
    ) : MeterValue

    /** Constants. */
    public companion object {
        /** Connected's link-quality segments (Paper "B · 1": `32 2 32 2 32`). */
        public const val SEGMENTS: Int = 3
    }
}

/** The meter's three arcs (Paper "B · 1", "B · 2", "B · 8"). */
public enum class MeterSize {
    /** Beside a button: 196 dp wide ([com.abid.handlebarlink.core.designsystem.theme.HlSizes.meter]). */
    COMPACT,

    /** Without a button: 316 dp. */
    WIDE,

    /** A retry counting down: 236 dp, a flatter arc. */
    RETRY,

    /** The BikePlay tab's meter: 340 dp. */
    HERO,

    /** The BikePlay tab's meter on its side: 280 dp. */
    HERO_PANE,
}

/**
 * The meter: a value arc ([value], [tone]) over a tick arc, [size] wide and as tall as Paper's box. Drawn in [ink]
 * alphas (white on the dark card; the tab's hero can pass its ink) with the knob's ring in [knobRing], the colour
 * behind it.
 */
@Composable
public fun HlMeter(
    value: MeterValue,
    tone: MeterTone,
    size: MeterSize,
    modifier: Modifier = Modifier,
    ink: Color = HlTheme.colors.inkInverse,
    knobRing: Color = HlTheme.colors.instrumentSolid,
) {
    val colors = HlTheme.colors
    val geometry = size.geometry()
    val accent by animateColorAsState(
        if (tone ==
            MeterTone.DANGER
        ) {
            colors.danger
        } else {
            colors.brand
        },
        HlTheme.motion.fade(),
    )
    val target = (value as? MeterValue.Progress)?.fraction ?: 0f
    val drains = (value as? MeterValue.Progress)?.draining == true
    val fraction by animateFloatAsState(
        target.coerceIn(0f, 1f),
        if (drains) tween(DRAIN_TICK_MS, easing = LinearEasing) else HlTheme.motion.fade(),
    )
    val width = size.width()
    Canvas(modifier.size(width, width * (geometry.boxHeight / geometry.boxWidth))) {
        val scale = this.size.width / geometry.boxWidth
        meter(geometry, scale, value, fraction, MeterInk(accent, ink, knobRing, tone))
    }
}

/**
 * The BikePlay tab's hero (Paper "BikePlay — flow" Tab 1–9, [pane] on its side Tab 1L–9L): the meter ([value],
 * [tone]) with the [state] word and its [detail] (up to two balanced lines) on the arc's base. The meter, its word and
 * its line are the whole status: nothing else repeats it (owner, 2026-11-20). Drawn in the page's ink, the knob's ring
 * in the page's ground.
 */
@Composable
public fun HlMeterHero(
    state: String,
    detail: String,
    value: MeterValue,
    tone: MeterTone,
    modifier: Modifier = Modifier,
    pane: Boolean = false,
) {
    val colors = HlTheme.colors
    val sizes = HlTheme.sizes
    val spacing = HlTheme.spacing
    val geometry = if (pane) MeterGeometry.HERO_PANE else MeterGeometry.HERO
    val width = if (pane) sizes.meterHeroPane else sizes.meterHero
    val height = width * (geometry.boxHeight / geometry.boxWidth)
    val gap = if (pane) spacing.s2 else spacing.s4
    Box(modifier.width(width).heightIn(min = height), contentAlignment = Alignment.BottomCenter) {
        HlMeter(
            value,
            tone,
            if (pane) MeterSize.HERO_PANE else MeterSize.HERO,
            Modifier.align(Alignment.TopCenter),
            ink = colors.ink,
            knobRing = colors.screen,
        )
        Column(
            // Kept below the arc's top: larger text grows the hero downwards, never over the status bar.
            Modifier.padding(top = height - if (pane) HeroPaneReadout else HeroReadout, bottom = gap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            HlText(
                state,
                if (pane) InstrumentType.stateWide else InstrumentType.heroState,
                colors.ink,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            HlText(
                detail,
                (if (pane) InstrumentType.detail else InstrumentType.detailWide).centred(),
                colors.ink.copy(alpha = HERO_DETAIL),
                Modifier.widthIn(max = if (pane) sizes.meterHeroTextPane else sizes.meterHeroText),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The meter's colours. */
private class MeterInk(
    val accent: Color,
    val ink: Color,
    val knobRing: Color,
    val tone: MeterTone,
)

private fun DrawScope.meter(
    g: MeterGeometry,
    scale: Float,
    value: MeterValue,
    fraction: Float,
    ink: MeterInk,
) {
    val centre = Offset(g.centreX * scale, g.centreY * scale)
    val start = TOP - g.sweep / 2
    val arc = Stroke(g.stroke * scale)

    fun part(
        color: Color,
        from: Float,
        to: Float,
    ) = drawArc(
        color,
        start + g.sweep * from / MeterGeometry.PATH_LENGTH,
        g.sweep * (to - from) / MeterGeometry.PATH_LENGTH,
        useCenter = false,
        topLeft = centre - Offset(g.radius * scale, g.radius * scale),
        size = Size(2 * g.radius * scale, 2 * g.radius * scale),
        style = arc,
    )
    // Alphas from the artboards: tracks #FFFFFF1F (lit states) and #FFFFFF29 (empty), ticks #FFFFFF33 (good),
    // #FFFFFF24 (dim and draining), #FF6B4A47 (Needs you).
    val ticks =
        when {
            value == MeterValue.Full -> ink.accent.copy(alpha = TICKS_DANGER)
            value is MeterValue.Empty || ink.tone == MeterTone.DANGER -> ink.ink.copy(alpha = TICKS_DIM)
            else -> ink.ink.copy(alpha = TICKS_LIT)
        }
    when (value) {
        MeterValue.Empty -> {
            part(ink.ink.copy(alpha = TRACK_EMPTY), 0f, MeterGeometry.PATH_LENGTH)
        }

        MeterValue.Full -> {
            part(ink.accent, 0f, MeterGeometry.PATH_LENGTH)
        }

        is MeterValue.Segments -> {
            repeat(value.count) { index ->
                val from = index * (MeterGeometry.SEGMENT + MeterGeometry.SEGMENT_GAP)
                val color = if (index < value.lit) ink.accent else ink.ink.copy(alpha = TRACK)
                part(color, from, from + MeterGeometry.SEGMENT)
            }
        }

        is MeterValue.Progress -> {
            part(ink.ink.copy(alpha = TRACK), 0f, MeterGeometry.PATH_LENGTH)
            if (fraction > 0f) part(ink.accent, 0f, fraction * MeterGeometry.PATH_LENGTH)
            val angle = (start + g.sweep * fraction) * PI / HALF_TURN
            val at = centre + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (g.radius * scale)
            val knob = g.knobRadius * scale
            val ring = g.knobStroke * scale
            // An SVG stroke is centred on the circle's edge: the ring reaches half its width outside the fill.
            drawCircle(ink.knobRing, radius = knob + ring / 2, center = at)
            drawCircle(ink.accent, radius = knob - ring / 2, center = at)
        }
    }
    tickArc(g, scale, centre, ticks)
}

/** The ticks: one dash every [MeterGeometry.tickPeriod] along the tick arc from its left end, as SVG dashes. */
private fun DrawScope.tickArc(
    g: MeterGeometry,
    scale: Float,
    centre: Offset,
    color: Color,
) {
    val start = TOP - g.tickSweep / 2
    val radius = g.tickRadius * scale
    repeat(g.tickCount) { index ->
        drawArc(
            color,
            start + g.tickSweep * (index * g.tickPeriod) / MeterGeometry.PATH_LENGTH,
            g.tickSweep * g.tickDash / MeterGeometry.PATH_LENGTH,
            useCenter = false,
            topLeft = centre - Offset(radius, radius),
            size = Size(2 * radius, 2 * radius),
            style = Stroke(g.tickStroke * scale),
        )
    }
}

private fun MeterSize.geometry(): MeterGeometry =
    when (this) {
        MeterSize.COMPACT -> MeterGeometry.COMPACT
        MeterSize.WIDE -> MeterGeometry.WIDE
        MeterSize.RETRY -> MeterGeometry.RETRY
        MeterSize.HERO -> MeterGeometry.HERO
        MeterSize.HERO_PANE -> MeterGeometry.HERO_PANE
    }

@Composable
private fun MeterSize.width(): Dp =
    when (this) {
        MeterSize.COMPACT -> HlTheme.sizes.meter
        MeterSize.WIDE -> HlTheme.sizes.meterWide
        MeterSize.RETRY -> HlTheme.sizes.meterRetry
        MeterSize.HERO -> HlTheme.sizes.meterHero
        MeterSize.HERO_PANE -> HlTheme.sizes.meterHeroPane
    }

/** The angle of 12 o'clock in Compose's clockwise degrees from 3 o'clock. */
private const val TOP = 270f
private const val HALF_TURN = 180.0

/** The once-a-second countdown: the draining arc moves linearly between updates. */
private const val DRAIN_TICK_MS = 1_000

// Alphas from the artboards: tracks #FFFFFF1F (lit states) and #FFFFFF29 (empty), ticks #FFFFFF33 (good), #FFFFFF24
// (dim and draining), #FF6B4A47 (Needs you).
private const val TRACK = 0.12f
private const val TRACK_EMPTY = 0.16f
private const val TICKS_LIT = 0.20f
private const val TICKS_DIM = 0.14f
private const val TICKS_DANGER = 0.28f

/** The hero's detail, #FFFFFFB8 on Night. */
private const val HERO_DETAIL = 0.72f

/**
 * The tallest readout at Paper's sizes (the word and two lines of detail with their gaps: 38 + 4 + 40 + 4 upright, 34 +
 * 2 + 36 + 2 on its side): the room it leaves above itself in the meter's box.
 */
private val HeroReadout = 86.dp
private val HeroPaneReadout = 74.dp

package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.InstrumentType
import com.abid.handlebarlink.core.designsystem.theme.MeterGeometry
import com.abid.handlebarlink.core.designsystem.theme.centred
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The BikePlay instrument (Paper "BikePlay card", design B "Instrument"; the owner chose it on 2026-11-20): a meter
// arc with ticks, the state word inside it, at most one full-width button. The meter is its own piece so the BikePlay
// tab's hero can use it too.

/** A reading beside the meter: its [value] over its [label] ("Strong" over "Link"). */
@Immutable
public data class InstrumentReading(
    /** The reading ("Strong"). */
    val value: String,
    /** What it is ("Link"). */
    val label: String,
)

/**
 * The card's one button: [primary] lime for what the rider does (Open, Add, Start, a fix, Try now), else the well with
 * an edge (dismissing).
 */
public data class InstrumentAction(
    /** The button's words. */
    val label: String,
    /** Lime (true) or the well with an edge. */
    val primary: Boolean,
    /** An icon before the words (Play, PlusBold). */
    @param:DrawableRes val icon: Int? = null,
    /** What a tap does. */
    val onClick: () -> Unit,
)

/**
 * The BikePlay instrument card (Paper "BikePlay card B — Instrument"): [title] and its mark top left, a chevron top
 * right (a tap anywhere but the button calls [onClick]); the meter ([value], [tone]) with the [state] word and the
 * [detail] line inside it, the [start] and [end] readings on its base (Connected); at most one [action] at the bottom.
 * 208 dp tall in every state (a minimum, for large text), full width; Night's glass or Bento's ink card, the edge in
 * danger when the tone is.
 */
@Composable
public fun HlInstrumentCard(
    title: String,
    state: String,
    detail: String,
    value: MeterValue,
    tone: MeterTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    start: InstrumentReading? = null,
    end: InstrumentReading? = null,
    action: InstrumentAction? = null,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val shape = RoundedCornerShape(HlTheme.radii.hero)
    val shadow = HlTheme.shadows.instrument
    val edge by animateColorAsState(
        if (tone == MeterTone.DANGER) colors.danger.copy(alpha = EDGE_DANGER) else colors.inkInverse.copy(alpha = EDGE),
        HlTheme.motion.fade(),
    )
    val press = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .heightIn(min = HlTheme.sizes.instrument)
            .dropShadow(
                shape,
                Shadow(radius = shadow.blur, color = colors.instrumentShadow, offset = DpOffset(0.dp, shadow.offsetY)),
            ).clip(shape)
            .background(colors.instrument)
            .border(HlTheme.strokes.hairline, edge, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ).padding(spacing.s16),
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        val size =
            when {
                (value as? MeterValue.Progress)?.draining == true -> MeterSize.RETRY
                action != null -> MeterSize.COMPACT
                else -> MeterSize.WIDE
            }
        // The meter's band: 112 dp beside a button, the whole inside (208 - 2 x 16) without one.
        val band = if (action != null) MeterBandHeight else HlTheme.sizes.instrument - spacing.s16 * 2
        Box(Modifier.fillMaxWidth().heightIn(min = band)) {
            Corner(title, Modifier.align(Alignment.TopStart))
            HlIcon(
                HlIcons.ChevronRight,
                colors.inkInverse.copy(alpha = CHEVRON),
                Modifier.align(Alignment.TopEnd).offset(y = -HlTheme.strokes.hairline),
                size = HlTheme.sizes.iconSmall,
            )
            MeterRow(
                Readout(state, detail, value, tone, size),
                start,
                end,
                Modifier.align(Alignment.BottomCenter),
            )
        }
        action?.let { InstrumentButton(it) }
    }
}

/** What the meter shows: the [state] word and its [detail] in the meter ([value], [tone], [size]). */
private class Readout(
    val state: String,
    val detail: String,
    val value: MeterValue,
    val tone: MeterTone,
    val size: MeterSize,
)

/** The meter with its readout, between the [start] and [end] readings when there are any. */
@Composable
private fun MeterRow(
    readout: Readout,
    start: InstrumentReading?,
    end: InstrumentReading?,
    modifier: Modifier,
) {
    val readings = start != null || end != null
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (readings) Arrangement.SpaceBetween else Arrangement.Center,
    ) {
        start?.let { Reading(it, Alignment.Start) }
        val fill = if (readout.size == MeterSize.RETRY) Modifier.weight(1f) else Modifier
        Gauge(readout.state, readout.detail, readout.value, readout.tone, readout.size, fill)
        end?.let { Reading(it, Alignment.End) }
    }
}

/** The card's corner: BikePlay's mark and its name, 2 dp in (Paper: `left: 2px`). */
@Composable
private fun Corner(
    title: String,
    modifier: Modifier,
) {
    val color = HlTheme.colors.inkInverse.copy(alpha = CORNER)
    Row(
        modifier.padding(start = HlTheme.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s6),
    ) {
        HlIcon(HlIcons.BikePlayMark, color, size = MarkSize)
        HlText(title, InstrumentType.label, color, maxLines = 1)
    }
}

/** The meter with the state word and the detail inside its arc, both centred on its base. */
@Composable
private fun Gauge(
    state: String,
    detail: String,
    value: MeterValue,
    tone: MeterTone,
    size: MeterSize,
    modifier: Modifier,
) {
    val colors = HlTheme.colors
    val wide = size == MeterSize.WIDE
    val stateStyle =
        when (size) {
            MeterSize.COMPACT -> InstrumentType.state
            MeterSize.WIDE -> InstrumentType.stateWide
            MeterSize.RETRY -> InstrumentType.stateRetry
            MeterSize.HERO -> InstrumentType.heroState
            MeterSize.HERO_PANE -> InstrumentType.stateWide
        }
    Box(
        modifier.heightIn(min = if (wide) WideGaugeHeight else MeterBandHeight),
        contentAlignment = Alignment.BottomCenter,
    ) {
        HlMeter(value, tone, size, Modifier.align(Alignment.TopCenter))
        Column(
            // Kept below the arc's top and the corner: larger text grows the card downwards, never into the corner.
            Modifier.padding(
                top = if (wide) WideReadoutTop else ReadoutTop,
                bottom = if (wide) HlTheme.spacing.s6 else 0.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (wide) HlTheme.spacing.s4 else HlTheme.spacing.s2),
        ) {
            HlText(state, stateStyle, colors.inkInverse, maxLines = 1, textAlign = TextAlign.Center)
            HlText(
                detail,
                (if (wide) InstrumentType.detailWide else InstrumentType.detail).centred(),
                colors.inkInverse.copy(alpha = DETAIL),
                // At most two balanced lines, 300 dp wide (Paper "B · 4": the intro wider than the arc).
                Modifier.widthIn(max = HlTheme.sizes.instrumentText),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A reading on the meter's base: 72 dp wide, aligned to its side ([alignment]); the end one in tabular figures. */
@Composable
private fun Reading(
    reading: InstrumentReading,
    alignment: Alignment.Horizontal,
) {
    val colors = HlTheme.colors
    val end = alignment == Alignment.End
    Column(
        Modifier.width(HlTheme.sizes.meterFoot),
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s2),
    ) {
        HlText(
            reading.value,
            if (end) InstrumentType.reading.copy(fontFeatureSettings = TABULAR) else InstrumentType.reading,
            colors.inkInverse,
            maxLines = 1,
        )
        HlText(reading.label, InstrumentType.readingLabel, colors.inkInverse.copy(alpha = READING_LABEL), maxLines = 1)
    }
}

/** The card's button: 52 dp, full width; lime with ink, or the well with an edge and white text. */
@Composable
private fun InstrumentButton(action: InstrumentAction) {
    val colors = HlTheme.colors
    val ink = if (action.primary) colors.brandInk else colors.inkInverse
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .heightIn(min = HlTheme.sizes.controlLarge)
            .clip(CircleShape)
            .background(if (action.primary) colors.brand else colors.inkInverse.copy(alpha = QUIET_FILL))
            .then(
                if (action.primary) {
                    Modifier
                } else {
                    Modifier.border(HlTheme.strokes.hairline, colors.inkInverse.copy(alpha = QUIET_EDGE), CircleShape)
                },
            ).clickable(press, LocalIndication.current, role = Role.Button, onClick = action.onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8, Alignment.CenterHorizontally),
    ) {
        action.icon?.let {
            HlIcon(it, ink, size = if (it == HlIcons.Play) HlTheme.sizes.iconMicro else HlTheme.sizes.iconTiny)
        }
        HlText(action.label, InstrumentType.button, ink, maxLines = 1)
    }
}

/** The corner mark (Paper: a 16 px icon). */
private val MarkSize = 16.dp

/** The meter's band beside a button and the gauge without one (Paper: 112 and 168 px). */
private val MeterBandHeight = 112.dp
private val WideGaugeHeight = 168.dp

/**
 * The least room above the state word: at Paper's sizes the readout (48–66 dp beside a button, the two-line "Not set
 * up" the tallest at 112 - 46; 64 dp without) still sits on the gauge's base, as drawn; larger text pushes the base
 * down instead of the words up over the corner.
 */
private val ReadoutTop = 46.dp
private val WideReadoutTop = 104.dp

/** Tabular figures (Paper: `font-feature-settings: "tnum"` on "5 GHz"). */
private const val TABULAR = "tnum"

// Alphas from the artboards: the edge #FFFFFF1F / #FF6B4A52, corner and detail #FFFFFFB8, reading labels #FFFFFFA3,
// chevron #FFFFFF8C, the quiet button #FFFFFF14 with a #FFFFFF2E edge.
private const val EDGE = 0.12f
private const val EDGE_DANGER = 0.32f
private const val CORNER = 0.72f
private const val DETAIL = 0.72f
private const val READING_LABEL = 0.64f
private const val CHEVRON = 0.55f
private const val QUIET_FILL = 0.08f
private const val QUIET_EDGE = 0.18f

package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.asin
import kotlin.math.sqrt

// The BikePlay instrument card's type and its meter's geometry (Paper "BikePlay card", design B "Instrument": the
// artboards "B · 1 … 9", Night and Bento). Values read with get_jsx / get_computed_styles on 2026-11-20.

/** The instrument card's text styles; colours come from the card. */
internal object InstrumentType {
    /** The corner label "BikePlay": 14/18 semibold. */
    val label: TextStyle = HlTypographyDefault.body.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.em)

    /** The state word beside a button ("Connected"): 24/28 semibold, -0.03em. */
    val state: TextStyle = HlTypographyDefault.figure.copy(lineHeight = 28.sp, letterSpacing = (-0.03).em)

    /** The state word without a button ("Starting"): 30/34 semibold, -0.035em. */
    val stateWide: TextStyle =
        HlTypographyDefault.figure.copy(
            fontSize = 30.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.035).em,
        )

    /** The state word while a retry counts down ("Couldn't connect"): 24/30 semibold, -0.035em. */
    val stateRetry: TextStyle = HlTypographyDefault.figure.copy(letterSpacing = (-0.035).em)

    /** The detail under the state word: 14/18 medium. */
    val detail: TextStyle = HlTypographyDefault.body.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.em)

    /** The detail without a button: 15/20 medium. */
    val detailWide: TextStyle = detail.copy(fontSize = 15.sp, lineHeight = 20.sp)

    /** A reading's value ("Strong", "5 GHz"): 17/20 semibold, -0.02em. */
    val reading: TextStyle = HlTypographyDefault.lead.copy(lineHeight = 20.sp)

    /** A reading's label ("Link", "Ch 36"): 13/16 medium. */
    val readingLabel: TextStyle = HlTypographyDefault.label.copy(lineHeight = 16.sp, letterSpacing = 0.em)

    /** The tab meter's state word on its side: 30/34 semibold, -0.035em ([stateWide]); upright the headline. */
    val heroState: TextStyle = HlTypographyDefault.headline

    /** A row's title beside an icon tile (Paper "BikePlay — flow" status list): 15/20 semibold, -0.02em. */
    val rowTitle: TextStyle = HlTypographyDefault.value.copy(lineHeight = 20.sp)

    /** A row's trailing value ("Paired", "Strong"): 13/17 medium. */
    val rowHint: TextStyle = HlTypographyDefault.label.copy(letterSpacing = 0.em)

    /** The card's button: 16/20 semibold, -0.01em. */
    val button: TextStyle =
        HlTypographyDefault.lead.copy(
            fontSize = 16.sp,
            lineHeight = 20.sp,
            letterSpacing = (-0.01).em,
        )

    /** A secondary or destructive button on the BikePlay tab: 16/20 semibold. */
    val buttonQuiet: TextStyle = button.copy(letterSpacing = 0.em)
}

/**
 * One meter arc as Paper draws it in an SVG box [boxWidth] x [boxHeight]: the value arc through ([endX], [endY]) and
 * its mirror on a circle of [radius] whose centre is on the box's vertical middle line, drawn [stroke] wide; the ticks
 * on a concentric circle of [tickRadius] across a chord of half-width [tickHalfChord], each [tickStroke] long
 * (radially), one dash of [tickDash] every [tickPeriod] along the tick arc (SVG `pathLength="100"`); the knob at the
 * end of a progress arc [knobRadius] with a [knobStroke] ring cut out of the card.
 */
@Suppress("LongParameterList") // One value per attribute of the artboard's SVG, as Paper draws it.
internal class MeterGeometry(
    val boxWidth: Float,
    val boxHeight: Float,
    val endX: Float,
    val endY: Float,
    val radius: Float,
    val stroke: Float,
    val tickRadius: Float,
    val tickHalfChord: Float,
    val tickStroke: Float,
    val tickPeriod: Float,
    val tickDash: Float,
    val knobRadius: Float,
    val knobStroke: Float,
) {
    /** The circle's centre, in box units. */
    val centreX: Float get() = boxWidth / 2
    val centreY: Float get() = endY + sqrt(radius * radius - (centreX - endX) * (centreX - endX))

    /** The value arc's sweep in degrees (180 for a half circle). */
    val sweep: Float get() = degrees(2 * asin((centreX - endX) / radius))

    /** The tick arc's sweep in degrees. */
    val tickSweep: Float get() = degrees(2 * asin(tickHalfChord / tickRadius))

    /** The ticks the dash pattern draws along the tick arc (whole dashes only). */
    val tickCount: Int get() = ((PATH_LENGTH - tickDash) / tickPeriod).toInt() + 1

    private fun degrees(radians: Float) = (radians * HALF_TURN / Math.PI).toFloat()

    companion object {
        /** SVG `pathLength="100"`: dash lengths are percent of the path. */
        const val PATH_LENGTH = 100f
        private const val HALF_TURN = 180.0

        /**
         * Beside a button (Paper "B · 1 Connected", "B · 4", "B · 5–7", "B · 9"): a 196 x 94 box, `M10 92 A88 88`
         * stroke 4; ticks `M32.2 54 A76 76` stroke 5, dashes 0.7 every 7.14. No knob in these artboards; a progress
         * arc here takes the retry meter's (same stroke).
         */
        val COMPACT: MeterGeometry =
            MeterGeometry(196f, 94f, 10f, 92f, 88f, 4f, 76f, 65.8f, 5f, 7.14f, 0.7f, 5.5f, 2.5f)

        /**
         * Without a button (Paper "B · 2 Starting", "B · 3 Connecting"): a 316 x 160 box, `M8 154 A150 150` stroke 5;
         * ticks `M40 86 A136 136` stroke 6, dashes 0.6 every 5 (0.6 + 4.4); the knob r 7 with a 3 ring.
         */
        val WIDE: MeterGeometry =
            MeterGeometry(316f, 160f, 8f, 154f, 150f, 5f, 136f, 118f, 6f, 5f, 0.6f, 7f, 3f)

        /**
         * While a retry counts down (Paper "B · 8 Couldn't connect"): a 236 x 94 box, `M10 92 A109.8 109.8` stroke 4
         * (less than a half circle); ticks `M39.1 54 A97.8 97.8` stroke 5, dashes 0.7 every 7.14; the knob r 5.5 with a
         * 2.5 ring.
         */
        val RETRY: MeterGeometry =
            MeterGeometry(236f, 94f, 10f, 92f, 109.8f, 4f, 97.8f, 78.9f, 5f, 7.14f, 0.7f, 5.5f, 2.5f)

        /**
         * The BikePlay tab's meter (Paper "BikePlay — flow" Tab 1–9): a 340 x 180 box, `M14 172 A156 156` stroke 6;
         * ticks `M48.76 102 A140 140` stroke 7, dashes 0.6 every 5; the knob r 8 with a 3 ring.
         */
        val HERO: MeterGeometry =
            MeterGeometry(340f, 180f, 14f, 172f, 156f, 6f, 140f, 121.24f, 7f, 5f, 0.6f, 8f, 3f)

        /**
         * The same on its side (Tab 1L–9L): a 280 x 150 box, `M10 144 A130 130` stroke 5; ticks `M39.54 86 A116 116`
         * stroke 6, dashes 0.6 every 5; the knob r 7 with a 3 ring.
         */
        val HERO_PANE: MeterGeometry =
            MeterGeometry(280f, 150f, 10f, 144f, 130f, 5f, 116f, 100.46f, 6f, 5f, 0.6f, 7f, 3f)

        /** Connected's link quality as three segments: `stroke-dasharray="32 2 32 2 32 100"`. */
        const val SEGMENT = 32f
        const val SEGMENT_GAP = 2f
    }
}

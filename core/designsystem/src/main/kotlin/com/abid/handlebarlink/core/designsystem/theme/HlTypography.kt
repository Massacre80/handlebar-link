package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.abid.handlebarlink.core.designsystem.R

/**
 * Text styles from the design's type tokens (`--text-*`, `--leading-*`, `--tracking-*`), in sp so they
 * follow the user's font size. Colours come from [HlColors] at the call site.
 */
@Immutable
public data class HlTypography(
    /** 12/16, small details. */
    val meta: TextStyle,
    /** 13/17, labels and secondary lines. */
    val label: TextStyle,
    /** 14/18, running text. */
    val body: TextStyle,
    /** 15/22, card titles. */
    val value: TextStyle,
    /** 17/22, prominent titles. */
    val lead: TextStyle,
    /** 20/26, screen titles. */
    val title: TextStyle,
    /** 46/46, hero numbers. */
    val display: TextStyle,
    /** 34/38, setup screens' titles. */
    val headline: TextStyle,
    /** 11/12, tab labels. */
    val tab: TextStyle,
    /** 11/12 with caps tracking: overlines; the text itself is upper-cased by the component. */
    val caps: TextStyle,
    /** 11/12 with wide tracking: setup's overlines ("STEP 1 OF 3"); upper-cased by the component. */
    val overline: TextStyle,
    /** 22/26, the odometer. */
    val figureSmall: TextStyle,
    /** 24/30, gauge values. */
    val figure: TextStyle,
    /** 32/34, large stat values. */
    val figureLarge: TextStyle,
    /** Integral CF 15/18, wide tracking: the bike's name. */
    val wordmark: TextStyle,
)

/** Inter Display, bundled (the phone has no internet on the bike). */
public val InterDisplay: FontFamily =
    FontFamily(
        Font(R.font.inter_display_regular, FontWeight.Normal),
        Font(R.font.inter_display_medium, FontWeight.Medium),
        Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    )

/** Integral CF, bundled. */
public val IntegralCf: FontFamily = FontFamily(Font(R.font.integral_cf_regular, FontWeight.Normal))

private val CssLineHeight =
    LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

private fun style(
    size: TextUnit,
    leading: TextUnit,
    weight: FontWeight,
    tracking: TextUnit,
    family: FontFamily = InterDisplay,
) = TextStyle(
    fontFamily = family,
    fontSize = size,
    lineHeight = leading,
    fontWeight = weight,
    letterSpacing = tracking,
    lineHeightStyle = CssLineHeight,
)

/** Tracking tokens (`--tracking-*`). */
internal object Tracking {
    val tight = (-0.01).em
    val tighter = (-0.02).em
    val heading = (-0.025).em
    val figure = (-0.03).em
    val hero = (-0.035).em
    val caps = 0.03.em
    val wide = 0.08.em
}

/** The type scale; the same in both themes. */
public val HlTypographyDefault: HlTypography =
    HlTypography(
        meta = style(12.sp, 16.sp, FontWeight.Medium, Tracking.tight),
        label = style(13.sp, 17.sp, FontWeight.Medium, Tracking.tight),
        body = style(14.sp, 18.sp, FontWeight.Normal, Tracking.tight),
        value = style(15.sp, 22.sp, FontWeight.SemiBold, Tracking.tighter),
        lead = style(17.sp, 22.sp, FontWeight.SemiBold, Tracking.tighter),
        title = style(20.sp, 26.sp, FontWeight.SemiBold, Tracking.heading),
        display = style(46.sp, 46.sp, FontWeight.SemiBold, Tracking.hero),
        headline = style(34.sp, 38.sp, FontWeight.SemiBold, Tracking.hero),
        tab = style(11.sp, 12.sp, FontWeight.Medium, Tracking.tight),
        caps = style(11.sp, 12.sp, FontWeight.Medium, Tracking.caps),
        overline = style(11.sp, 12.sp, FontWeight.Medium, Tracking.wide),
        figureSmall = style(22.sp, 26.sp, FontWeight.SemiBold, Tracking.heading),
        figure = style(24.sp, 30.sp, FontWeight.SemiBold, Tracking.heading),
        figureLarge = style(32.sp, 34.sp, FontWeight.SemiBold, Tracking.figure),
        wordmark = style(15.sp, 18.sp, FontWeight.Normal, Tracking.wide, IntegralCf),
    )

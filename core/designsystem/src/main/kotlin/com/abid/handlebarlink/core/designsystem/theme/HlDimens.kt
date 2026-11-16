package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing tokens (`--spacing-*`). */
@Immutable
public data class HlSpacing(
    /** 2 dp. */
    val s2: Dp = 2.dp,
    /** 6 dp. */
    val s6: Dp = 6.dp,
    /** 10 dp. */
    val s10: Dp = 10.dp,
    /** 18 dp. */
    val s18: Dp = 18.dp,
    /** 22 dp. */
    val s22: Dp = 22.dp,
    /** 28 dp. */
    val s28: Dp = 28.dp,
    /** 4 dp. */
    val s4: Dp = 4.dp,
    /** 8 dp. */
    val s8: Dp = 8.dp,
    /** 12 dp. */
    val s12: Dp = 12.dp,
    /** 14 dp. */
    val s14: Dp = 14.dp,
    /** 16 dp: screen edges. */
    val s16: Dp = 16.dp,
    /** 20 dp. */
    val s20: Dp = 20.dp,
    /** 24 dp. */
    val s24: Dp = 24.dp,
)

/** Size tokens (`--size-*`). Interactive sizes are at least 48 dp (accessibility minimum). */
@Immutable
public data class HlSizes(
    /** Icons. */
    val icon: Dp = 24.dp,
    /** Large controls (buttons). */
    val controlLarge: Dp = 52.dp,
    /** A tab's height. */
    val tab: Dp = 52.dp,
    /** The selected tab's width. */
    val tabWidth: Dp = 74.dp,
    /** The bottom bar's height. */
    val bar: Dp = 70.dp,
    /** Icon chips in cards. */
    val chip: Dp = 36.dp,
    /** Minimum touch target. */
    val touchTarget: Dp = 48.dp,
    /** Bento status pill. */
    val pill: Dp = 32.dp,
    /** Night status pill. */
    val pillCompact: Dp = 26.dp,
    /** Status dots. */
    val dot: Dp = 7.dp,
    /** Small status dots. */
    val dotSmall: Dp = 6.dp,
    /** Large status dots. */
    val dotLarge: Dp = 8.dp,
    /** Night arc gauges. */
    val gauge: Dp = 150.dp,
    /** The arc's centre line inside the gauge box. */
    val gaugeInset: Dp = 12.dp,
    /** Bento tick ring. */
    val ring: Dp = 112.dp,
    /** The CarPlay signal ring. */
    val signal: Dp = 56.dp,
    /** Icon circles, round buttons, option icons. */
    val avatar: Dp = 44.dp,
    /** The CarPlay card's button. */
    val action: Dp = 48.dp,
    /** Small icons. */
    val iconSmall: Dp = 20.dp,
    /** Medium icons. */
    val iconMedium: Dp = 22.dp,
    /** The check mark. */
    val iconMicro: Dp = 14.dp,
    /** Tiny icons. */
    val iconTiny: Dp = 18.dp,
    /** Radios and checks. */
    val indicator: Dp = 24.dp,
    /** A tick's height. */
    val tick: Dp = 12.dp,
    /** Night device tiles. */
    val tile: Dp = 140.dp,
    /** Bento bike card. */
    val heroCard: Dp = 224.dp,
    /** Bento stat cards. */
    val statCard: Dp = 186.dp,
    /** The floating tab bar. */
    val tabBar: Dp = 64.dp,
    /** The widest content column; wider windows centre it (the compact width class ends at 600 dp). */
    val contentMax: Dp = 600.dp,
    /** Sheet handle width. */
    val handleWidth: Dp = 40.dp,
    /** Sheet handle height. */
    val handleHeight: Dp = 5.dp,
)

/** Stroke widths (`--stroke-*`) and the dashed-outline pattern (`--dash-*`). */
@Immutable
public data class HlStrokes(
    /** Card and pill edges. */
    val hairline: Dp = 1.dp,
    /** Dashed slots, radios, the selected option. */
    val outline: Dp = 1.5.dp,
    /** Arc track; tick width. */
    val track: Dp = 2.dp,
    /** Arc progress. */
    val progress: Dp = 2.5.dp,
    /** CarPlay ring ticks. */
    val signal: Dp = 4.dp,
    /** Bento ring ticks. */
    val ring: Dp = 12.dp,
    /** Dash length. */
    val dashOn: Dp = 6.dp,
    /** Gap between dashes. */
    val dashOff: Dp = 4.dp,
)

/** One drop shadow (`--shadow-*`: x 0, [offsetY], [blur]); its colour is [HlColors.shadow]. */
@Immutable
public data class HlShadow(
    /** Vertical offset. */
    val offsetY: Dp,
    /** Blur radius. */
    val blur: Dp,
)

/** Drop shadows; they differ per look. */
@Immutable
public data class HlShadows(
    /** The tab bar. */
    val bar: HlShadow,
    /** The selected tab. */
    val chip: HlShadow,
    /** Floating cards. */
    val floating: HlShadow,
)

/** Bento shadows (`design/bento.css`). */
public val BentoShadows: HlShadows =
    HlShadows(bar = HlShadow(10.dp, 28.dp), chip = HlShadow(4.dp, 12.dp), floating = HlShadow(10.dp, 28.dp))

/** Night shadows (`design/night.css`). */
public val NightShadows: HlShadows =
    HlShadows(bar = HlShadow(12.dp, 32.dp), chip = HlShadow(0.dp, 0.dp), floating = HlShadow(18.dp, 40.dp))

/** Corner radius tokens (`--radius-*`). */
@Immutable
public data class HlRadii(
    /** Cards. */
    val card: Dp = 24.dp,
    /** Hero cards. */
    val hero: Dp = 28.dp,
    /** Pills and circles (CSS 999px). */
    val full: Dp = 999.dp,
    /** Night's floating card. */
    val floating: Dp = 20.dp,
    /** The sheet handle. */
    val handle: Dp = 3.dp,
    /** Elements nested in cards. */
    val nested: Dp = 12.dp,
    /** Avatars. */
    val avatar: Dp = 10.dp,
    /** Buttons and inputs. */
    val control: Dp = 16.dp,
)

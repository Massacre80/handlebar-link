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
    /** Stacked buttons of one action group (owner, 2026-11-20). */
    val buttonStack: Dp = 8.dp,
    /**
     * Between an action group and the card, group or list next to it, and between a sheet's or column's groups
     * (header, options, footnote, actions) (owner, 2026-11-20).
     */
    val groupGap: Dp = 14.dp,
    /** Buttons side by side (a dialog's). */
    val buttonRow: Dp = 12.dp,
    /** A tab page's content below the app header ("Component · App header"). */
    val headerGap: Dp = 8.dp,
)

/**
 * The clear gap between a row's or card's text and whatever trails it (a button, a value, a chevron, a switch, a radio,
 * a battery): [HlSpacing.s16] everywhere, so a subtext never runs up to its control (owner, 2026-11-20). Rows keep it
 * through `HlRowText`.
 */
public val HlSpacing.trailingGap: Dp get() = s16

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
    /** The handlebar switch render on Home's Handlebar card (the CarPlay badge's row, larger to read the keys). */
    val handlebarRender: Dp = 88.dp,
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
    /** An icon row's chevron (Paper "BikePlay — flow" status list). */
    val chevron: Dp = 16.dp,
    /** Radios and checks. */
    val indicator: Dp = 24.dp,
    /** The battery's width, terminal included (iOS style, the number inside). */
    val batteryWidth: Dp = 30.dp,
    /** The battery's height. */
    val batteryHeight: Dp = 14.dp,
    /** The battery terminal's width. */
    val batteryNub: Dp = 1.5.dp,
    /** The gap between the battery's body and its terminal. */
    val batteryNubGap: Dp = 1.dp,
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
    /**
     * The floating tab rail's width (landscape): the portrait bar turned on its side, as thin as the bar is tall (the
     * tab's 52 dp and the bar's 6 dp inset on each side; owner 2026-11-20: the 86 dp rail was too wide).
     */
    val tabRail: Dp = 64.dp,
    /** The widest content column; wider windows centre it (the compact width class ends at 600 dp). */
    val contentMax: Dp = 600.dp,
    /**
     * The widest line of a hero's centred subtext on its side (Paper "Tab 1L — No iPhone yet, landscape (Night)": "Pair
     * it once here. CarPlay then" over "connects by itself on every ride.").
     */
    val heroText: Dp = 260.dp,
    /** The BikePlay instrument card's height, the same in every state (Paper "BikePlay card B — Instrument"). */
    val instrument: Dp = 208.dp,
    /** The instrument meter's arc beside a button (Paper "B · 1 Connected"). */
    val meter: Dp = 196.dp,
    /** The instrument meter's arc when the card has no button (Paper "B · 2 Starting", "B · 3 Connecting"). */
    val meterWide: Dp = 316.dp,
    /** The instrument meter's arc while a retry counts down (Paper "B · 8 Couldn't connect"). */
    val meterRetry: Dp = 236.dp,
    /** A reading beside the meter ("Strong" over "Link", "5 GHz" over "Ch 36"). */
    val meterFoot: Dp = 72.dp,
    /** The instrument's detail line at its widest: two balanced lines (Paper "B · 4 Not set up"). */
    val instrumentText: Dp = 300.dp,
    /** The BikePlay tab's meter (Paper "BikePlay — flow", Tab 1–9). */
    val meterHero: Dp = 340.dp,
    /** The BikePlay tab's meter on its side (Tab 1L–9L). */
    val meterHeroPane: Dp = 280.dp,
    /** The tab meter's detail line at its widest. */
    val meterHeroText: Dp = 290.dp,
    /** The same on its side. */
    val meterHeroTextPane: Dp = 240.dp,
    /** A tab hero's dial (Paper "Devices 1 — No intercom yet"). */
    val dial: Dp = 168.dp,
    /** The mark inside a tab hero's dial. */
    val dialIcon: Dp = 30.dp,
    /** Sheet handle width. */
    val handleWidth: Dp = 40.dp,
    /** Sheet handle height. */
    val handleHeight: Dp = 5.dp,
    /** A switch's track width (Paper "CarPlay — flow v2": 44 x 26). */
    val switchWidth: Dp = 44.dp,
    /** A switch's track height. */
    val switchHeight: Dp = 26.dp,
    /** A switch's thumb (20 dp, 3 dp from the track's edge). */
    val switchThumb: Dp = 20.dp,
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
    /** The BikePlay instrument card; its colour is [HlColors.instrumentShadow]. */
    val instrument: HlShadow,
)

/** Bento shadows (`design/bento.css`). */
public val BentoShadows: HlShadows =
    HlShadows(
        bar = HlShadow(10.dp, 28.dp),
        chip = HlShadow(4.dp, 12.dp),
        floating = HlShadow(10.dp, 28.dp),
        instrument = HlShadow(10.dp, 24.dp),
    )

/** Night shadows (`design/night.css`). */
public val NightShadows: HlShadows =
    HlShadows(
        bar = HlShadow(12.dp, 32.dp),
        chip = HlShadow(0.dp, 0.dp),
        floating = HlShadow(18.dp, 40.dp),
        instrument = HlShadow(18.dp, 40.dp),
    )

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

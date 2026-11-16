package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens, named as in the design file (`design/bento.css`, `design/night.css`): one instance per
 * theme. Components use these names only, never raw colours.
 */
@Immutable
public data class HlColors(
    /** Primary text and icons. */
    val ink: Color,
    /** Inactive icons. */
    val inkSoft: Color,
    /** Secondary text. */
    val inkMuted: Color,
    /** Text on [surfaceInverse]. */
    val inkInverse: Color,
    /** Secondary text on [surfaceInverse]. */
    val inkInverseMuted: Color,
    /** Sheets and dialogs. */
    val ground: Color,
    /** Cards. */
    val panel: Color,
    /** Selected items (the active tab). */
    val raisedHi: Color,
    /** Icon chips inside cards. */
    val raisedLo: Color,
    /** Recessed areas (pills, inputs). */
    val well: Color,
    /** Hairlines and card edges. */
    val line: Color,
    /** Faint dividers. */
    val lineSoft: Color,
    /** Dashed outlines (an empty slot). */
    val lineStrong: Color,
    /** The emphasised card (CarPlay). */
    val surfaceInverse: Color,
    /** The emphasised card's edge. */
    val surfaceInverseEdge: Color,
    /** The brand colour. */
    val brand: Color,
    /** Brand, lighter. */
    val brandHi: Color,
    /** Brand, darker. */
    val brandLo: Color,
    /** Brand on a light ground (readable on white). */
    val brandDeep: Color,
    /** Content on [brand]. */
    val brandInk: Color,
    /** A faint brand wash. */
    val brandTint: Color,
    /** Positive state. */
    val success: Color,
    /** A faint positive wash. */
    val successTint: Color,
    /** Failure state. */
    val danger: Color,
    /** Floating translucent bars (tab bar). */
    val glass: Color,
    /** The glass edge. */
    val glassEdge: Color,
    /** Lines on glass. */
    val glassLine: Color,
    /** Content on glass. */
    val onGlass: Color,
    /** Secondary content on glass. */
    val onGlassMuted: Color,
    /** Screen background. */
    val screen: Color,
    /** The status pill. */
    val pill: Color,
    /** The background glow's centre (equal to [screen] when there is no glow). */
    val glowInner: Color,
    /** The background glow's middle stop. */
    val glowMid: Color,
    /** "Connected" indicators. */
    val signal: Color,
    /** The primary button on [surfaceInverse]. */
    val action: Color,
    /** Content on [action]. */
    val onAction: Color,
    /** Gauge and tick tracks. */
    val track: Color,
    /** Gauge and tick fill. */
    val gauge: Color,
    /** Drop shadows. */
    val shadow: Color,
)

/** Bento: light, solid cards (`design/bento.css`). */
public val BentoColors: HlColors =
    HlColors(
        ink = Color(0xFF0D0F0B),
        inkSoft = Color(0xFF6B7064),
        inkMuted = Color(0xFF7A7F73),
        inkInverse = Color(0xFFFFFFFF),
        inkInverseMuted = Color(0x9EFFFFFF),
        ground = Color(0xFFFFFFFF),
        panel = Color(0xFFFFFFFF),
        raisedHi = Color(0xFFFFFFFF),
        raisedLo = Color(0xFFF2F3EE),
        well = Color(0xFFF2F3EE),
        line = Color(0xFFE7E8E2),
        lineSoft = Color(0xFFF0F1EC),
        lineStrong = Color(0xFFB4B8AE),
        surfaceInverse = Color(0xFF0D0F0B),
        surfaceInverseEdge = Color(0x000D0F0B),
        brand = Color(0xFFC8F03C),
        brandHi = Color(0xFFD4F55E),
        brandLo = Color(0xFFBCE42B),
        brandDeep = Color(0xFF7FA80F),
        brandInk = Color(0xFF0D0F0B),
        brandTint = Color(0xFFF6FCE0),
        success = Color(0xFF3FA34D),
        successTint = Color(0xFFEAF6EC),
        danger = Color(0xFFFF6B4A),
        glass = Color(0x8CFFFFFF),
        glassEdge = Color(0xB3FFFFFF),
        glassLine = Color(0x1A0D0F0B),
        onGlass = Color(0xFF0D0F0B),
        onGlassMuted = Color(0x9E0D0F0B),
        screen = Color(0xFFF2F3EE),
        pill = Color(0xCFFFFFFF),
        glowInner = Color(0xFFF2F3EE),
        glowMid = Color(0xFFF2F3EE),
        signal = Color(0xFF7FA80F),
        action = Color(0xFFC8F03C),
        onAction = Color(0xFF0D0F0B),
        track = Color(0x1F0D0F0B),
        gauge = Color(0xFF7FA80F),
        shadow = Color(0x140D0F0B),
    )

/** Night: dark, glass cards over a green glow (`design/night.css`). */
public val NightColors: HlColors =
    HlColors(
        ink = Color(0xFFFFFFFF),
        inkSoft = Color(0x8CFFFFFF),
        inkMuted = Color(0x9EFFFFFF),
        inkInverse = Color(0xFFFFFFFF),
        inkInverseMuted = Color(0x9EFFFFFF),
        ground = Color(0xFF0A0B08),
        panel = Color(0x12FFFFFF),
        raisedHi = Color(0x24FFFFFF),
        raisedLo = Color(0x1AFFFFFF),
        well = Color(0x12FFFFFF),
        line = Color(0x1AFFFFFF),
        lineSoft = Color(0x0FFFFFFF),
        lineStrong = Color(0x38FFFFFF),
        surfaceInverse = Color(0xB81E2118),
        surfaceInverseEdge = Color(0x47C8F03C),
        brand = Color(0xFFC8F03C),
        brandHi = Color(0xFFD4F55E),
        brandLo = Color(0xFFBCE42B),
        brandDeep = Color(0xFF7FA80F),
        brandInk = Color(0xFF0D0F0B),
        brandTint = Color(0x14C8F03C),
        success = Color(0xFF3FA34D),
        successTint = Color(0x243FA34D),
        danger = Color(0xFFFF6B4A),
        glass = Color(0x12FFFFFF),
        glassEdge = Color(0x1AFFFFFF),
        glassLine = Color(0x1AFFFFFF),
        onGlass = Color(0xFFFFFFFF),
        onGlassMuted = Color(0x8CFFFFFF),
        screen = Color(0xFF0A0B08),
        pill = Color(0x12FFFFFF),
        glowInner = Color(0xFF3B4A14),
        glowMid = Color(0xFF1A2009),
        signal = Color(0xFFC8F03C),
        action = Color(0xFFFFFFFF),
        onAction = Color(0xFF0D0F0B),
        track = Color(0x1FFFFFFF),
        gauge = Color(0xFFE9EDE0),
        shadow = Color(0x59000000),
    )

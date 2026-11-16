package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** The two looks of the design; the rider picks one in settings. */
public enum class HlThemeVariant {
    /** Dark, bike photo as hero, glass cards. */
    NIGHT,

    /** Light, card grid. */
    BENTO,
}

private val LocalVariant = staticCompositionLocalOf { HlThemeVariant.NIGHT }
private val LocalColors = staticCompositionLocalOf { NightColors }
private val LocalTypography = staticCompositionLocalOf { HlTypographyDefault }
private val LocalSpacing = staticCompositionLocalOf { HlSpacing() }
private val LocalSizes = staticCompositionLocalOf { HlSizes() }
private val LocalRadii = staticCompositionLocalOf { HlRadii() }
private val LocalStrokes = staticCompositionLocalOf { HlStrokes() }
private val LocalShadows = staticCompositionLocalOf { NightShadows }

/**
 * The app's design system: a fully custom theme (Android "Custom design systems in Compose"), not
 * Material. Values change only when the rider switches theme, so they are static locals.
 */
@Composable
public fun HlTheme(
    variant: HlThemeVariant,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalVariant provides variant,
        LocalColors provides colorsFor(variant),
        LocalTypography provides HlTypographyDefault,
        LocalSpacing provides HlSpacing(),
        LocalSizes provides HlSizes(),
        LocalRadii provides HlRadii(),
        LocalStrokes provides HlStrokes(),
        LocalShadows provides shadowsFor(variant),
        content = content,
    )
}

/** The colours of [variant]. */
public fun colorsFor(variant: HlThemeVariant): HlColors =
    when (variant) {
        HlThemeVariant.NIGHT -> NightColors
        HlThemeVariant.BENTO -> BentoColors
    }

/** The shadows of [variant]. */
public fun shadowsFor(variant: HlThemeVariant): HlShadows =
    when (variant) {
        HlThemeVariant.NIGHT -> NightShadows
        HlThemeVariant.BENTO -> BentoShadows
    }

/** Reads the current theme's tokens. */
public object HlTheme {
    /** Which look is active (some components lay out differently per look). */
    public val variant: HlThemeVariant
        @Composable @ReadOnlyComposable
        get() = LocalVariant.current

    /** Colour tokens. */
    public val colors: HlColors
        @Composable @ReadOnlyComposable
        get() = LocalColors.current

    /** Text styles. */
    public val typography: HlTypography
        @Composable @ReadOnlyComposable
        get() = LocalTypography.current

    /** Spacing tokens. */
    public val spacing: HlSpacing
        @Composable @ReadOnlyComposable
        get() = LocalSpacing.current

    /** Size tokens. */
    public val sizes: HlSizes
        @Composable @ReadOnlyComposable
        get() = LocalSizes.current

    /** Corner radius tokens. */
    public val radii: HlRadii
        @Composable @ReadOnlyComposable
        get() = LocalRadii.current

    /** Stroke widths. */
    public val strokes: HlStrokes
        @Composable @ReadOnlyComposable
        get() = LocalStrokes.current

    /** Drop shadows. */
    public val shadows: HlShadows
        @Composable @ReadOnlyComposable
        get() = LocalShadows.current
}

package com.abid.handlebarlink.ui.frame

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.window.core.layout.WindowSizeClass
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * How the app arranges its pages for the window (docs/PLAN.md "Landscape layout"), decided from the window size
 * classes as the Android docs recommend ("Use window size classes": `currentWindowAdaptiveInfoV2().windowSizeClass`),
 * never from the device or the orientation alone.
 */
enum class WindowLayout {
    /** One column with the floating tab bar at the bottom: the phone upright (the owner's approved design). */
    COLUMN,

    /**
     * The phone on its side (compact height, at least medium width): the tab rail on the end edge, where CarPlay
     * keeps its dock, and pages in two panes side by side, so nothing hides under a bar or below the fold.
     */
    PANES,
}

/**
 * [WindowLayout.PANES] when the window is short (compact height, under 480 dp: "99.78 % of phones in landscape")
 * and wide enough for two panes (at least medium width, 600 dp); [WindowLayout.COLUMN] otherwise, which keeps
 * portrait phones, tablets and a small split-screen window on the column with its bottom bar.
 */
fun windowLayout(sizeClass: WindowSizeClass): WindowLayout =
    if (
        !sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND) &&
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    ) {
        WindowLayout.PANES
    } else {
        WindowLayout.COLUMN
    }

/** The window's layout now; it changes when the phone turns or the window is resized. */
@Composable
fun currentWindowLayout(): WindowLayout = windowLayout(currentWindowAdaptiveInfoV2().windowSizeClass)

/**
 * The layout pages follow, provided once by the app's root. Previews and screenshot tests get [WindowLayout.COLUMN]
 * unless they provide [WindowLayout.PANES] themselves (the landscape screenshots), so the portrait references hold.
 */
val LocalWindowLayout = compositionLocalOf { WindowLayout.COLUMN }

/**
 * The widest line of a hero's centred subtext in [layout]: [heroText] (HlSizes.heroText, Paper "Tab 1L") on its side,
 * where a pane would otherwise run it into two long lines; unbounded upright, where the column sets it.
 */
fun heroTextMax(
    layout: WindowLayout,
    heroText: Dp,
): Dp = if (layout == WindowLayout.PANES) heroText else Dp.Unspecified

/** [heroTextMax] for the current layout, for `Modifier.widthIn(max = …)`. */
@Composable
fun heroTextMax(): Dp = heroTextMax(LocalWindowLayout.current, HlTheme.sizes.heroText)

/**
 * A tab page's end padding beside the rail, beyond the mirrored [side] every page already pads: the rail's [width],
 * its [margin] to the screen's edge, and a gap to the content as wide as the content's own margin at the start
 * ([side] + [margin]), so start margin, gap and the rail's end margin are all equal.
 */
fun railSpace(
    side: Dp,
    margin: Dp,
    width: Dp,
): Dp = width + margin + side + margin

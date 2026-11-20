package com.abid.handlebarlink.carplay

import android.view.RoundedCorner
import android.view.Surface
import android.view.WindowInsets
import com.abid.handlebarlink.carplay.engine.CarPlayInsets
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The parts of this phone's screen that CarPlay's controls must stay out of, in screen pixels, as Android reports them
 * for the default display (owner 2026-11-20: padding that suits any phone, measured, never fixed numbers):
 * - [cornerRadius]: the largest of the screen's rounded corners (`WindowInsets.getRoundedCorner`);
 * - [cutoutTop], [cutoutBottom]: how deep the display cutout reaches in from the upright phone's top and bottom
 *   edges (`DisplayCutout` safe insets, turned back to the upright phone);
 * - [navigationBar]: the navigation bar's depth along its edge, shown or hidden (`WindowInsets.Type.navigationBars`,
 *   ignoring visibility; with gesture navigation the strip of the swipe-up handle at the bottom). The handle's strip,
 *   not the wider strip the system keeps for its gestures (`mandatorySystemGestures`, 84 px against 63 on the Poco):
 *   that one left too much room under the dock (owner, 2026-11-20), and the handle's strip matches the iPhone's own
 *   home-indicator inset.
 */
data class ScreenEdges(
    val cornerRadius: Int = 0,
    val cutoutTop: Int = 0,
    val cutoutBottom: Int = 0,
    val navigationBar: Int = 0,
) {
    companion object {
        /** A plain rectangle with nothing in the way. */
        val NONE: ScreenEdges = ScreenEdges()
    }
}

/**
 * How far in from each edge of [area] (canvas pixels, shown over the whole [window] in [orientation]) CarPlay keeps its
 * controls, for a screen with [edges]: the area's safe area ([CarPlayViewArea.safe]). In screen pixels first:
 * - every edge stays clear of the rounded corners: a rectangle inset by r(1 - 1/√2) on each side has its corners
 *   exactly on a corner of radius r, so nothing square is cut off;
 * - upright, the bottom also clears the navigation bar's strip, the swipe-up handle's: CarPlay docks along the bottom
 *   there, so the dock never sits under it; and the top and bottom clear the cutout;
 * - on its side the bottom clears only the corners, like the top: told the real size ([realWidthMm]) CarPlay docks on
 *   the right edge (Poco, 136 mm), so nothing sits along the bottom; and where a wider screen keeps the dock along the
 *   bottom the owner still preferred the smaller inset (2026-11-20; the iPhone too keeps a smaller home-indicator
 *   inset in landscape). The window already leaves the cutout's strip out ([carPlayWindow]).
 *
 * Then scaled to the area across each axis (the display stretches the area over the window per axis) and rounded up,
 * so the clear band is never thinner than measured. Each inset is held under a quarter of its side, so a broken
 * measurement can never leave CarPlay without room.
 */
internal fun safeInsets(
    edges: ScreenEdges,
    orientation: CarPlayOrientation,
    window: WindowSize,
    area: CarPlayViewArea,
): CarPlayInsets {
    val corner = ceil(edges.cornerRadius * (1 - sqrt(HALF))).toInt()
    val upright = orientation == CarPlayOrientation.PORTRAIT
    val top = if (upright) max(edges.cutoutTop, corner) else corner
    val bottom = if (upright) maxOf(edges.cutoutBottom, edges.navigationBar, corner) else corner

    fun across(pixels: Int) = scaled(pixels, area.width, window.width)

    fun down(pixels: Int) = scaled(pixels, area.height, window.height)
    return CarPlayInsets(left = across(corner), top = down(top), right = across(corner), bottom = down(bottom))
}

/** [pixels] of a [screenSide] scaled to an [areaSide], rounded up, held under a quarter of the side. */
private fun scaled(
    pixels: Int,
    areaSide: Int,
    screenSide: Int,
): Int {
    if (pixels <= 0 || screenSide <= 0) return 0
    val value = ceil(pixels.toDouble() * areaSide / screenSide).toInt()
    return value.coerceAtMost((areaSide - 1) / QUARTER)
}

/**
 * Reads [ScreenEdges] from the [insets] of the display's largest window (`WindowMetrics.getWindowInsets`), whose cutout
 * and bars are those of the phone's current [rotation] (`Display.getRotation`): the cutout is turned back to the
 * upright phone, so the answer does not depend on how the phone is held when CarPlay connects.
 */
internal fun screenEdges(
    insets: WindowInsets,
    rotation: Int,
): ScreenEdges {
    val radius =
        CORNERS.maxOfOrNull { insets.getRoundedCorner(it)?.radius ?: 0 } ?: 0
    val cutout = insets.displayCutout
    val (top, bottom) =
        if (cutout == null) {
            0 to 0
        } else {
            uprightEnds(
                rotation,
                cutout.safeInsetLeft,
                cutout.safeInsetTop,
                cutout.safeInsetRight,
                cutout.safeInsetBottom,
            )
        }
    // The navigation bar's depth on its edge, visible or not (the display hides it).
    val bar = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
    val strip = maxOf(bar.bottom, bar.left, bar.right)
    return ScreenEdges(cornerRadius = radius, cutoutTop = top, cutoutBottom = bottom, navigationBar = strip)
}

/**
 * The upright phone's top and bottom from insets ([left], [top], [right], [bottom]) taken in [rotation]: turned a
 * quarter to the left (ROTATION_90) the upright top is on the left, a half turn swaps top and bottom, and turned a
 * quarter to the right (ROTATION_270) the top is on the right (`Display.getRotation`).
 */
@Suppress("LongParameterList") // A rotation and the four insets it applies to.
internal fun uprightEnds(
    rotation: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): Pair<Int, Int> =
    when (rotation) {
        Surface.ROTATION_90 -> left to right
        Surface.ROTATION_180 -> bottom to top
        Surface.ROTATION_270 -> right to left
        else -> top to bottom
    }

private const val HALF = 0.5
private const val QUARTER = 4

private val CORNERS =
    listOf(
        RoundedCorner.POSITION_TOP_LEFT,
        RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_RIGHT,
        RoundedCorner.POSITION_BOTTOM_LEFT,
    )

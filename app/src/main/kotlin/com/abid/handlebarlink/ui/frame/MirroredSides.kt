package com.abid.handlebarlink.ui.frame

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * [base]'s left and right sides made equal: the larger of the two on both, nothing at the top or bottom. On its
 * side the phone has its cutout (and a three-button navigation bar) on one short edge only; padding that edge alone
 * pushed every page and the bar off the screen's centre (owner, 2026-11-20: "not centred"). Mirrored, the pages, the
 * tab rail and the page under a sheet share one frame centred on the screen, still clear of the cutout. Upright the
 * sides are zero, so portrait is unchanged.
 */
@Stable
internal class MirroredSides(
    private val base: WindowInsets,
) : WindowInsets {
    private fun side(
        density: Density,
        layoutDirection: LayoutDirection,
    ) = maxOf(base.getLeft(density, layoutDirection), base.getRight(density, layoutDirection))

    override fun getLeft(
        density: Density,
        layoutDirection: LayoutDirection,
    ): Int = side(density, layoutDirection)

    override fun getRight(
        density: Density,
        layoutDirection: LayoutDirection,
    ): Int = side(density, layoutDirection)

    override fun getTop(density: Density): Int = 0

    override fun getBottom(density: Density): Int = 0

    override fun equals(other: Any?): Boolean = other is MirroredSides && other.base == base

    override fun hashCode(): Int = base.hashCode()

    override fun toString(): String = "MirroredSides($base)"
}

/** The safe drawing area's sides, mirrored ([MirroredSides]): the frame every page and the tab rail share. */
val WindowInsets.Companion.pageSides: WindowInsets
    @Composable get() = MirroredSides(WindowInsets.safeDrawing)

/**
 * What a tab page keeps clear of: the status bar (and a top cutout) and the mirrored sides. The bottom is the tab
 * bar's or the rail's room, given to the page as its content padding.
 */
val WindowInsets.Companion.pageTop: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).union(WindowInsets.pageSides)

/** What a full page (no tab bar) keeps clear of: the top and bottom system bars and the mirrored sides. */
val WindowInsets.Companion.pageAll: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical).union(WindowInsets.pageSides)

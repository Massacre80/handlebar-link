package com.abid.handlebarlink.carplay

import android.view.Surface
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.engine.CarPlayInsets
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import org.junit.Test

/**
 * The safe insets measured from any phone ([safeInsets], [uprightEnds]). Expected values worked by hand (shown) or by
 * `diplay_display.py`'s independent `safe_insets`, never by the code under test.
 */
class ScreenEdgesTest {
    @Test
    fun `a plain rectangle with nothing in the way needs no insets`() {
        val area = CarPlayViewArea(1920, 960)

        assertThat(safeInsets(ScreenEdges.NONE, CarPlayOrientation.LANDSCAPE, WindowSize(2160, 1080), area))
            .isEqualTo(CarPlayInsets.NONE)
    }

    @Test
    fun `rounded corners alone inset every edge by r times one less the square root of a half`() {
        // r = 100: 100 x (1 - 0.70711) = 29.29 -> 30 screen pixels; the area is the window's size, so 30 each.
        val edges = ScreenEdges(cornerRadius = 100)

        assertThat(safeInsets(edges, CarPlayOrientation.PORTRAIT, WindowSize(1080, 2400), CarPlayViewArea(1080, 2400)))
            .isEqualTo(CarPlayInsets(30, 30, 30, 30))
    }

    @Test
    fun `the cutout and the navigation bar count only upright, the navigation bar at the bottom`() {
        // No corners; cutout 80 at the top, navigation bar 60; area = window, so screen pixels stay as they are.
        val edges = ScreenEdges(cutoutTop = 80, navigationBar = 60)

        assertThat(safeInsets(edges, CarPlayOrientation.PORTRAIT, WindowSize(1080, 2400), CarPlayViewArea(1080, 2400)))
            .isEqualTo(CarPlayInsets(0, 80, 0, 60))
        // On its side the window already leaves the cutout out, and CarPlay docks on a side edge, not the bottom.
        assertThat(safeInsets(edges, CarPlayOrientation.LANDSCAPE, WindowSize(2320, 1080), CarPlayViewArea(2320, 1080)))
            .isEqualTo(CarPlayInsets.NONE)
    }

    @Test
    fun `screen pixels scale to the area per axis and round up`() {
        // Navigation bar 84 on a 2246-high window, area 1920 high: 84 x 1920 / 2246 = 71.8 -> 72.
        val edges = ScreenEdges(navigationBar = 84)

        assertThat(safeInsets(edges, CarPlayOrientation.PORTRAIT, WindowSize(1080, 2246), CarPlayViewArea(922, 1920)))
            .isEqualTo(CarPlayInsets(0, 0, 0, 72))
    }

    @Test
    fun `a broken measurement never takes more than a quarter of a side`() {
        // (100 - 1) / 4 = 24.
        val edges = ScreenEdges(navigationBar = 5_000)

        assertThat(safeInsets(edges, CarPlayOrientation.PORTRAIT, WindowSize(100, 100), CarPlayViewArea(100, 100)))
            .isEqualTo(CarPlayInsets(0, 0, 0, 24))
    }

    @Test
    fun `the cutout is turned back to the upright phone whichever way it is held`() {
        // A notch 86 deep at the upright top, as each rotation reports it (left, top, right, bottom).
        assertThat(uprightEnds(Surface.ROTATION_0, 0, 86, 0, 0)).isEqualTo(86 to 0)
        assertThat(uprightEnds(Surface.ROTATION_90, 86, 0, 0, 0)).isEqualTo(86 to 0)
        assertThat(uprightEnds(Surface.ROTATION_180, 0, 0, 0, 86)).isEqualTo(86 to 0)
        assertThat(uprightEnds(Surface.ROTATION_270, 0, 0, 86, 0)).isEqualTo(86 to 0)
    }
}

package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/** The tab rail's spacing: its tabs (five here, the app's four below) spread over the height the app gives it. */
class RailGapTest {
    private fun gap(height: Dp) = railGap(height, inset = 6.dp, tab = 52.dp, count = 5, minimum = 4.dp)

    @Test
    fun `the tabs share the height left after them and the insets`() {
        // The emulator on its side: 411 dp less the status and navigation bars (24 + 24) and 8 dp at each end.
        // 347 - 2 x 6 - 5 x 52 = 75 dp over four gaps.
        assertThat(gap(347.dp)).isEqualTo(18.75.dp)
    }

    @Test
    fun `a short rail keeps the smallest gap`() {
        assertThat(gap(200.dp)).isEqualTo(4.dp)
    }

    @Test
    fun `a rail exactly as tall as its tabs and smallest gaps keeps that gap`() {
        assertThat(gap(288.dp)).isEqualTo(4.dp)
    }

    @Test
    fun `without a height the rail wraps its tabs`() {
        assertThat(gap(Dp.Infinity)).isEqualTo(4.dp)
    }

    @Test
    fun `one tab has no gap to share`() {
        assertThat(railGap(347.dp, 6.dp, 52.dp, count = 1, minimum = 4.dp)).isEqualTo(4.dp)
    }

    private fun tab(height: Dp) = railTab(height, inset = 6.dp, count = 5, minimumGap = 4.dp, tallest = 74.dp)

    @Test
    fun `a tall rail gives each tab the bar's tab turned on its side`() {
        // 5 x 74 + 2 x 6 + 4 x 4 = 398 dp fits in 420.
        assertThat(tab(420.dp)).isEqualTo(74.dp)
        assertThat(tab(Dp.Infinity)).isEqualTo(74.dp)
    }

    @Test
    fun `a short rail shares its height so the tabs and the smallest gaps fit`() {
        // The Poco on its side, about 330 dp: (330 - 2 x 6 - 4 x 4) / 5 = 60.4 dp, still taller than the 52 dp width.
        assertThat(tab(330.dp)).isEqualTo(60.4.dp)
    }

    @Test
    fun `the app's four tabs get the bar's tab turned on its side, on the Poco and the emulator`() {
        // Since Rides was dropped (owner, 2026-11-20). The Poco on its side, about 330 dp: (330 - 2 x 6 - 3 x 4) / 4
        // = 76.5 dp would fit, so each tab is the bar's 74; the gaps share 330 - 2 x 6 - 4 x 74 = 22 dp three ways.
        assertThat(railTab(330.dp, inset = 6.dp, count = 4, minimumGap = 4.dp, tallest = 74.dp)).isEqualTo(74.dp)
        assertThat(railGap(330.dp, inset = 6.dp, tab = 74.dp, count = 4, minimum = 4.dp)).isEqualTo(22.dp / 3)
        // The emulator, 347 dp: 347 - 2 x 6 - 4 x 74 = 39 dp over three gaps.
        assertThat(railTab(347.dp, inset = 6.dp, count = 4, minimumGap = 4.dp, tallest = 74.dp)).isEqualTo(74.dp)
        assertThat(railGap(347.dp, inset = 6.dp, tab = 74.dp, count = 4, minimum = 4.dp)).isEqualTo(13.dp)
    }
}

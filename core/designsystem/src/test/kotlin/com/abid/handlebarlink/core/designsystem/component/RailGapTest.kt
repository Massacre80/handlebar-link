package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/** The tab rail's spacing: its five tabs spread over the height the app gives it. */
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
}

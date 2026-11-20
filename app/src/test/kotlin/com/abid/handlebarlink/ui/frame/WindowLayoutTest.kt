package com.abid.handlebarlink.ui.frame

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/**
 * The layout decision (docs/PLAN.md "Landscape layout"). Windows go through the same breakpoint set the app's
 * `currentWindowAdaptiveInfoV2()` uses (BREAKPOINTS_V2), sized in dp as Android reports them.
 */
class WindowLayoutTest {
    private fun layoutFor(
        widthDp: Float,
        heightDp: Float,
    ) = windowLayout(WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(widthDp, heightDp))

    @Test
    fun `the emulator on its side gets panes`() {
        // 2400 x 1080 px at 420 dpi.
        assertThat(layoutFor(914f, 411f)).isEqualTo(WindowLayout.PANES)
    }

    @Test
    fun `the Poco on its side gets panes`() {
        // 2246 x 1080 px at 440 dpi.
        assertThat(layoutFor(817f, 393f)).isEqualTo(WindowLayout.PANES)
    }

    @Test
    fun `a phone upright keeps the column`() {
        assertThat(layoutFor(411f, 914f)).isEqualTo(WindowLayout.COLUMN)
    }

    @Test
    fun `a tablet on its side keeps the column, its height is not compact`() {
        assertThat(layoutFor(1280f, 800f)).isEqualTo(WindowLayout.COLUMN)
    }

    @Test
    fun `a short narrow window keeps the column, two panes would not fit`() {
        assertThat(layoutFor(500f, 400f)).isEqualTo(WindowLayout.COLUMN)
    }

    @Test
    fun `the width boundary is 600 dp`() {
        assertThat(layoutFor(599f, 400f)).isEqualTo(WindowLayout.COLUMN)
        assertThat(layoutFor(600f, 400f)).isEqualTo(WindowLayout.PANES)
    }

    @Test
    fun `the height boundary is 480 dp`() {
        assertThat(layoutFor(900f, 479f)).isEqualTo(WindowLayout.PANES)
        assertThat(layoutFor(900f, 480f)).isEqualTo(WindowLayout.COLUMN)
    }
}

package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.core.designsystem.theme.HlSizes
import com.abid.handlebarlink.core.designsystem.theme.HlSpacing
import org.junit.Test

/**
 * The app header's height, which every tab page leaves above its content (Paper "Component · App header"): the values
 * come from the artboard's measures, not from the function.
 */
class AppHeaderHeightTest {
    private fun height(
        night: Boolean,
        compact: Boolean,
        wordmarkLine: Dp = 18.dp,
    ) = appHeaderHeight(night, compact, wordmarkLine, HlSpacing(), HlSizes())

    @Test
    fun `Night upright stacks the wordmark over the pill, 78 dp at font 1`() {
        // 18 + 18 (the wordmark's line) + 4 + 26 (the pill) + 12.
        assertThat(height(night = true, compact = false)).isEqualTo(78.dp)
    }

    @Test
    fun `Night upright grows with the wordmark at large text`() {
        // Font 1.5: the 18 sp line is 27 dp.
        assertThat(height(night = true, compact = false, wordmarkLine = 27.dp)).isEqualTo(87.dp)
    }

    @Test
    fun `Bento upright is the 48 dp row with 8 dp above and below`() {
        assertThat(height(night = false, compact = false)).isEqualTo(64.dp)
    }

    @Test
    fun `on its side both looks are one 56 dp row`() {
        assertThat(height(night = true, compact = true)).isEqualTo(56.dp)
        assertThat(height(night = false, compact = true)).isEqualTo(56.dp)
    }

    @Test
    fun `on its side a wordmark taller than the touch target grows the row`() {
        assertThat(height(night = true, compact = true, wordmarkLine = 52.dp)).isEqualTo(60.dp)
    }

    @Test
    fun `Bento's row ignores the wordmark it does not show`() {
        assertThat(height(night = false, compact = true, wordmarkLine = 52.dp)).isEqualTo(56.dp)
    }
}

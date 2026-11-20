package com.abid.handlebarlink.ui.frame

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/** The pages' room for the bar or rail follows the current value, not the one a page was first given. */
class LivePaddingTest {
    @Test
    fun `a page holding the padding sees the value change when the insets arrive`() {
        val state = mutableStateOf(PaddingValues(end = 118.dp, bottom = 0.dp))
        val held = LivePadding(state)

        state.value = PaddingValues(end = 166.dp, bottom = 24.dp)

        assertThat(held.calculateRightPadding(LayoutDirection.Ltr)).isEqualTo(166.dp)
        assertThat(held.calculateBottomPadding()).isEqualTo(24.dp)
    }

    @Test
    fun `every side is passed through`() {
        val held = LivePadding(mutableStateOf(PaddingValues(start = 1.dp, top = 2.dp, end = 3.dp, bottom = 4.dp)))

        assertThat(held.calculateLeftPadding(LayoutDirection.Ltr)).isEqualTo(1.dp)
        assertThat(held.calculateTopPadding()).isEqualTo(2.dp)
        assertThat(held.calculateRightPadding(LayoutDirection.Ltr)).isEqualTo(3.dp)
        assertThat(held.calculateBottomPadding()).isEqualTo(4.dp)
        assertThat(held.calculateLeftPadding(LayoutDirection.Rtl)).isEqualTo(3.dp)
    }
}

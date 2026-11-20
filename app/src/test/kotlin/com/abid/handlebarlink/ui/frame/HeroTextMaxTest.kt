package com.abid.handlebarlink.ui.frame

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.core.designsystem.theme.HlSizes
import org.junit.Test

/** A hero's centred subtext keeps to the design's 260 dp on its side only (Paper "Tab 1L"). */
class HeroTextMaxTest {
    private val heroText = HlSizes().heroText

    @Test
    fun `on its side a hero's subtext keeps to the design's 260 dp`() {
        assertThat(heroText).isEqualTo(260.dp)
        assertThat(heroTextMax(WindowLayout.PANES, heroText)).isEqualTo(260.dp)
    }

    @Test
    fun `upright the column sets the width, not the landscape limit`() {
        assertThat(heroTextMax(WindowLayout.COLUMN, heroText)).isEqualTo(Dp.Unspecified)
    }
}

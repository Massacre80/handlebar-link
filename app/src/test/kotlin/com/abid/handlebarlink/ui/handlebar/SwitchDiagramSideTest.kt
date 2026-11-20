package com.abid.handlebarlink.ui.handlebar

import androidx.compose.ui.unit.Constraints
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/**
 * The switch diagram's side ([diagramSide]): a square as large as the room allows, never past the 1024 px photo
 * (blown up, it blurred on the phone on its side). Sizes are the devices' pixels.
 */
class SwitchDiagramSideTest {
    @Test
    fun `upright the column's width sets the side`() {
        // The Poco upright: 393 dp less 2 x 16 dp at 2.75 = 993 px, in a scrolling column (height unbounded).
        assertThat(diagramSide(width = 993, height = Constraints.Infinity, photo = PHOTO)).isEqualTo(993)
    }

    @Test
    fun `on its side the pane's height sets the side`() {
        // The emulator on its side: the pane is 1000 px wide and 744 px high under the title.
        assertThat(diagramSide(width = 1000, height = 744, photo = PHOTO)).isEqualTo(744)
    }

    @Test
    fun `a narrow pane's width sets the side`() {
        assertThat(diagramSide(width = 600, height = 744, photo = PHOTO)).isEqualTo(600)
    }

    @Test
    fun `the photo is never blown up past its pixels`() {
        // The 600 dp column at 2.625 (the old landscape page): 1491 px wide, unbounded height.
        assertThat(diagramSide(width = 1491, height = Constraints.Infinity, photo = PHOTO)).isEqualTo(PHOTO)
        assertThat(diagramSide(width = 1491, height = 1200, photo = PHOTO)).isEqualTo(PHOTO)
    }

    private companion object {
        /** handlebar_switch.webp is 1024 x 1024 px (drawable-nodpi). */
        const val PHOTO = 1024
    }
}

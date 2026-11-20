package com.abid.handlebarlink.ui.frame

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/** The mirrored sides that keep every page centred on a phone with its cutout on one short edge. */
class MirroredSidesTest {
    private val density = Density(1f)

    private fun sides(base: WindowInsets) =
        MirroredSides(base).let {
            listOf(
                it.getLeft(density, LayoutDirection.Ltr),
                it.getTop(density),
                it.getRight(density, LayoutDirection.Ltr),
                it.getBottom(density),
            )
        }

    @Test
    fun `a cutout on the left is mirrored on the right`() {
        // The emulator turned once: a 126 px cutout on the left, the status bar on top, the gesture bar below.
        assertThat(sides(WindowInsets(left = 126, top = 63, right = 0, bottom = 63))).isEqualTo(listOf(126, 0, 126, 0))
    }

    @Test
    fun `a cutout on the right is mirrored on the left`() {
        assertThat(sides(WindowInsets(left = 0, top = 63, right = 126, bottom = 63))).isEqualTo(listOf(126, 0, 126, 0))
    }

    @Test
    fun `a side navigation bar wider than the cutout sets both sides`() {
        assertThat(sides(WindowInsets(left = 86, right = 132))).isEqualTo(listOf(132, 0, 132, 0))
    }

    @Test
    fun `upright there are no sides, so portrait is unchanged`() {
        assertThat(sides(WindowInsets(left = 0, top = 126, right = 0, bottom = 63))).isEqualTo(listOf(0, 0, 0, 0))
    }
}

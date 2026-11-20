package com.abid.handlebarlink.ui.frame

import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

/**
 * The end padding beside the rail. A page's content then ends at width - side - railSpace; the rail's start is at
 * width - side - margin - rail. Their gap must equal the content's start margin (side + margin).
 */
class RailSpaceTest {
    @Test
    fun `the gap to the rail equals the content's start margin`() {
        val side = 48.dp
        val margin = 16.dp
        val rail = 86.dp
        val screen = 914.dp
        val contentEnd = screen - side - railSpace(side, margin, rail)
        val railStart = screen - side - margin - rail
        assertThat(railStart - contentEnd).isEqualTo(side + margin)
    }

    @Test
    fun `without a cutout the margins are the 16 dp margin alone`() {
        assertThat(railSpace(0.dp, 16.dp, 86.dp)).isEqualTo(118.dp)
    }
}

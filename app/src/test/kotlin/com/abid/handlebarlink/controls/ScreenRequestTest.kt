package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

/** The page a handlebar press asks MainActivity for, by name in its extra; anything else is ignored. */
class ScreenRequestTest {
    @Test
    fun `a request comes back by its name, and an unknown or missing one is ignored`() {
        assertThat(screenRequest(ScreenRequest.OPEN_CARPLAY.name)).isEqualTo(ScreenRequest.OPEN_CARPLAY)
        assertThat(screenRequest(ScreenRequest.SWITCH.name)).isEqualTo(ScreenRequest.SWITCH)
        assertThat(screenRequest("open_carplay")).isNull()
        assertThat(screenRequest("com.carlink.app")).isNull()
        assertThat(screenRequest(null)).isNull()
    }
}

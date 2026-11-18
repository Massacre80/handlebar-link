package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import org.junit.Test

class BrightnessStepTest {
    @Test
    fun `a step up is brighter and a step down dimmer`() {
        assertThat(brightnessStep(100, up = true)).isGreaterThan(100)
        assertThat(brightnessStep(100, up = false)).isLessThan(100)
    }

    @Test
    fun `the ends hold`() {
        assertThat(brightnessStep(255, up = true)).isEqualTo(255)
        assertThat(brightnessStep(1, up = false)).isEqualTo(1)
    }

    @Test
    fun `ten steps go from the dimmest to the brightest`() {
        var level = 1
        repeat(10) { level = brightnessStep(level, up = true) }
        assertThat(level).isEqualTo(255)
    }

    @Test
    fun `low levels still move (at least one level)`() {
        assertThat(brightnessStep(2, up = true)).isGreaterThan(2)
        assertThat(brightnessStep(2, up = false)).isEqualTo(1)
    }
}

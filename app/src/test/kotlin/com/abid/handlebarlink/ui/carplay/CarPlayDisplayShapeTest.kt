package com.abid.handlebarlink.ui.carplay

import android.content.pm.ActivityInfo
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import org.junit.Test

/** The CarPlay display's shape and the screen orientation it locks, from the canvas the iPhone was told. */
class CarPlayDisplayShapeTest {
    @Test
    fun `a landscape canvas locks landscape and keeps its shape`() {
        val shape = displayShape(CarPlayConfig(widthPixels = 2246, heightPixels = 1080), CarPlayOrientation.PORTRAIT)

        // What was reported wins over the next attempt's choice: the picture on screen is the reported one.
        assertThat(shape).isEqualTo(DisplayShape(CarPlayOrientation.LANDSCAPE, 2246f / 1080f))
        assertThat(screenOrientation(shape.orientation)).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
    }

    @Test
    fun `a portrait canvas locks portrait`() {
        val shape = displayShape(CarPlayConfig(widthPixels = 1080, heightPixels = 2246), CarPlayOrientation.LANDSCAPE)

        assertThat(shape).isEqualTo(DisplayShape(CarPlayOrientation.PORTRAIT, 1080f / 2246f))
        assertThat(screenOrientation(shape.orientation)).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)
    }

    @Test
    fun `before any attempt the next attempt's orientation is locked and the picture fills the window`() {
        assertThat(displayShape(null, CarPlayOrientation.LANDSCAPE))
            .isEqualTo(DisplayShape(CarPlayOrientation.LANDSCAPE, null))
        assertThat(displayShape(null, CarPlayOrientation.PORTRAIT))
            .isEqualTo(DisplayShape(CarPlayOrientation.PORTRAIT, null))
    }
}

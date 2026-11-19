package com.abid.handlebarlink.ui.carplay

import android.content.pm.ActivityInfo
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import com.abid.handlebarlink.carplay.engine.android.CarPlaySurfaceBounds
import org.junit.Test

/**
 * The CarPlay display's shape, the screen orientation it locks and the picture's place, from the canvas the iPhone was
 * told. Expected bounds are `diplay_display.py`'s (carplay/engine `src/test/resources/display/`, run with
 * `python3 -I`), pasted.
 */
class CarPlayDisplayShapeTest {
    private val square =
        CarPlayConfig(
            widthPixels = 1920,
            heightPixels = 1920,
            widthMillimetres = 300,
            heightMillimetres = 300,
            viewAreas = listOf(CarPlayViewArea(1920, 922), CarPlayViewArea(922, 1920)),
        )

    @Test
    fun `a square starting on its landscape area locks landscape and shows that area`() {
        val shape = displayShape(square, CarPlayOrientation.PORTRAIT)

        // What was reported wins over the next attempt's choice: the picture on screen is the reported one.
        assertThat(shape)
            .isEqualTo(DisplayShape(CarPlayOrientation.LANDSCAPE, PictureSpec(1920, 1920, CarPlayViewArea(1920, 922))))
        assertThat(screenOrientation(shape.orientation)).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
        // poco-landscape bounds=(0, 0, 2246, 2249)
        assertThat(pictureLayout(checkNotNull(shape.picture), 2246, 1080).bounds)
            .isEqualTo(CarPlaySurfaceBounds(0, 0, 2246, 2249))
    }

    @Test
    fun `a square starting on its portrait area locks portrait and shows that area`() {
        val shape = displayShape(square.copy(initialViewArea = 1), CarPlayOrientation.LANDSCAPE)

        assertThat(shape)
            .isEqualTo(DisplayShape(CarPlayOrientation.PORTRAIT, PictureSpec(1920, 1920, CarPlayViewArea(922, 1920))))
        assertThat(screenOrientation(shape.orientation)).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT)
        // poco-portrait bounds=(0, 0, 2249, 2246)
        assertThat(pictureLayout(checkNotNull(shape.picture), 1080, 2246).bounds)
            .isEqualTo(CarPlaySurfaceBounds(0, 0, 2249, 2246))
    }

    @Test
    fun `a plain canvas is its own area and keeps its shape`() {
        val shape = displayShape(CarPlayConfig(widthPixels = 2246, heightPixels = 1080), CarPlayOrientation.PORTRAIT)

        assertThat(shape)
            .isEqualTo(DisplayShape(CarPlayOrientation.LANDSCAPE, PictureSpec(2246, 1080, CarPlayViewArea(2246, 1080))))
        // fit content=(0.0, 119.14514692787174, 2000.0, 961.7097061442565) bounds=(0, 119, 2000, 962)
        assertThat(pictureLayout(checkNotNull(shape.picture), 2000, 1200).bounds)
            .isEqualTo(CarPlaySurfaceBounds(0, 119, 2000, 962))
    }

    @Test
    fun `before any attempt the chosen orientation locks the screen and the view fills it`() {
        assertThat(displayShape(null, CarPlayOrientation.LANDSCAPE))
            .isEqualTo(DisplayShape(CarPlayOrientation.LANDSCAPE, null))
        assertThat(displayShape(null, CarPlayOrientation.PORTRAIT))
            .isEqualTo(DisplayShape(CarPlayOrientation.PORTRAIT, null))
    }
}

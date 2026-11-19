package com.abid.handlebarlink.carplay.engine.android

import android.view.MotionEvent
import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import com.abid.handlebarlink.carplay.engine.android.touch.CarPlayTouch
import com.abid.handlebarlink.carplay.engine.android.touch.CarPlayTouch.Pointers
import org.junit.Test

/**
 * The canvas's place in the window and the touches through it, against `diplay_display.py` (carplay/engine's
 * `src/test/resources/display/`, run with `python3 -I diplay_display.py`): a transcription of DiPlay's `contentRect`
 * (CarPlayHostActivity.kt:6152-6166), `CarPlayVideoLayout.fit`, `CarPlaySurfaceBounds.from` and `CarPlayTouchMapper`
 * in doubles. The expected numbers are its printed output, pasted; floats are compared within 1e-3 of a pixel or 1e-6
 * of a fraction, as our code works in Float like DiPlay's.
 */
class CarPlayPictureLayoutTest {
    private fun assertLayout(
        layout: CarPlayPictureLayout,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
    ) {
        assertThat(layout.left.toDouble()).isCloseTo(left, PIXEL)
        assertThat(layout.top.toDouble()).isCloseTo(top, PIXEL)
        assertThat(layout.width.toDouble()).isCloseTo(width, PIXEL)
        assertThat(layout.height.toDouble()).isCloseTo(height, PIXEL)
    }

    /** One finger down at [x], [y] of the window (the view the layout is in). */
    private fun touch(
        layout: CarPlayPictureLayout,
        x: Float,
        y: Float,
    ): Pair<Double, Double> {
        val contact =
            CarPlayTouch
                .contacts(
                    Pointers(MotionEvent.ACTION_MOVE, 0, intArrayOf(4), floatArrayOf(x), floatArrayOf(y)),
                    layout,
                ).single()
        return contact.x to contact.y
    }

    private fun assertTouch(
        actual: Pair<Double, Double>,
        x: Double,
        y: Double,
    ) {
        assertThat(actual.first).isCloseTo(x, FRACTION)
        assertThat(actual.second).isCloseTo(y, FRACTION)
    }

    @Test
    fun `the Poco's landscape area fills the landscape window and the square's rest falls below it`() {
        val layout = CarPlayPictureLayout.of(1920, 1920, CarPlayViewArea(1920, 922), 2246, 1080)
        // poco-landscape content=(0.0, 0.0, 2246.0, 2249.0238611713667) bounds=(0, 0, 2246, 2249)
        assertLayout(layout, 0.0, 0.0, 2246.0, 2249.0238611713667)
        assertThat(layout.bounds).isEqualTo(CarPlaySurfaceBounds(0, 0, 2246, 2249))
        assertTouch(touch(layout, 0f, 0f), 0.0, 0.0)
        assertTouch(touch(layout, 1123f, 540f), 0.5, 0.24010416666666665)
        assertTouch(touch(layout, 2245f, 1079f), 0.9995547640249333, 0.4797636959876543)
        assertTouch(touch(layout, 561.5f, 810f), 0.25, 0.36015625)
    }

    @Test
    fun `the Poco's portrait area fills the portrait window and the square's rest falls to its right`() {
        val layout = CarPlayPictureLayout.of(1920, 1920, CarPlayViewArea(922, 1920), 1080, 2246)
        // poco-portrait content=(0.0, 0.0, 2249.0238611713667, 2246.0) bounds=(0, 0, 2249, 2246)
        assertLayout(layout, 0.0, 0.0, 2249.0238611713667, 2246.0)
        assertThat(layout.bounds).isEqualTo(CarPlaySurfaceBounds(0, 0, 2249, 2246))
        assertTouch(touch(layout, 540f, 1123f), 0.24010416666666665, 0.5)
        assertTouch(touch(layout, 1079f, 2245f), 0.4797636959876543, 0.9995547640249333)
        assertTouch(touch(layout, 270f, 1684.5f), 0.12005208333333332, 0.75)
    }

    @Test
    fun `with a cutout strip the landscape area still fills the narrower window`() {
        val layout = CarPlayPictureLayout.of(1920, 1920, CarPlayViewArea(1920, 960), 2157, 1080)
        // poco-landscape-cutout89 content=(0.0, 0.0, 2157.0, 2160.0) bounds=(0, 0, 2157, 2160)
        assertLayout(layout, 0.0, 0.0, 2157.0, 2160.0)
        assertThat(layout.bounds).isEqualTo(CarPlaySurfaceBounds(0, 0, 2157, 2160))
        assertTouch(touch(layout, 1078.5f, 540f), 0.5, 0.25)
        assertTouch(touch(layout, 539.25f, 810f), 0.25, 0.375)
    }

    @Test
    fun `a whole-canvas area keeps the canvas's shape, centred in a window of another shape`() {
        val layout = CarPlayPictureLayout.of(2246, 1080, CarPlayViewArea(2246, 1080), 2000, 1200)
        // fit content=(0.0, 119.14514692787174, 2000.0, 961.7097061442565) bounds=(0, 119, 2000, 962)
        assertLayout(layout, 0.0, 119.14514692787174, 2000.0, 961.7097061442565)
        assertThat(layout.bounds).isEqualTo(CarPlaySurfaceBounds(0, 119, 2000, 962))
        assertTouch(touch(layout, 1000f, 600f), 0.5, 0.5)
        assertTouch(touch(layout, 10f, 200f), 0.005, 0.0840740740740741)
        assertTouch(touch(layout, 1999f, 1000f), 0.9995, 0.9159259259259258)
    }

    @Test
    fun `an area away from the corner moves the canvas left, and touches land in that area`() {
        val layout = CarPlayPictureLayout.of(1920, 720, CarPlayViewArea(1280, 720, originX = 640), 2246, 1080)
        // offset-area content=(-1123.0, 0.0, 3369.0, 1080.0) bounds=(-1123, 0, 3369, 1080)
        assertLayout(layout, -1123.0, 0.0, 3369.0, 1080.0)
        assertThat(layout.bounds).isEqualTo(CarPlaySurfaceBounds(-1123, 0, 3369, 1080))
        assertTouch(touch(layout, 0f, 0f), 0.3333333333333333, 0.0)
        assertTouch(touch(layout, 1123f, 540f), 0.6666666666666666, 0.5)
        assertTouch(touch(layout, 2245f, 1079f), 0.9997031760166222, 0.9990740740740741)
    }

    @Test
    fun `in the surface placed at the bounds a touch lands where it lands in the window`() {
        val layout = CarPlayPictureLayout.of(1920, 720, CarPlayViewArea(1280, 720, originX = 640), 2246, 1080)
        val surface = layout.inSurface()
        // The window's (1123, 540) is the surface's (1123 + 1123, 540); offset-area: (0.6666666666666666, 0.5).
        assertTouch(touch(surface, 2246f, 540f), 0.6666666666666666, 0.5)
        assertThat(surface.left.toDouble()).isCloseTo(0.0, PIXEL)
    }

    @Test
    fun `touches outside the canvas are clamped to its edges`() {
        val layout = CarPlayPictureLayout.of(2246, 1080, CarPlayViewArea(2246, 1080), 2000, 1200)
        assertTouch(touch(layout, -5f, 10f), 0.0, 0.0)
        assertTouch(touch(layout, 2500f, 1199f), 1.0, 1.0)
    }

    private companion object {
        const val PIXEL = 1e-3
        const val FRACTION = 1e-6
    }
}

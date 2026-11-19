package com.abid.handlebarlink.carplay

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import org.junit.Test

/**
 * The canvas told to the iPhone. Expected values are worked out by hand from the rules (DiPlay's even sides and 4K
 * canvas limit, millimetres = pixels / dpi x 25.4), never taken from the function.
 */
class CarPlayCanvasTest {
    @Test
    fun `the Poco F1 in portrait reports its whole screen and its physical width`() {
        // Poco F1: 1080 x 2246 at 403.411 dpi, 60 Hz. 1080 / 403.411 x 25.4 = 68.00 mm.
        val config = carPlayCanvas(1080, 2246, xdpi = 403.411f, fallbackDpi = 440, refreshRate = 60f)

        assertThat(config).isEqualTo(
            CarPlayConfig(widthPixels = 1080, heightPixels = 2246, widthMillimetres = 68, maxFps = 60),
        )
    }

    @Test
    fun `the window's rotation does not matter, and landscape puts the long side across`() {
        // Measured while the phone lay on its side: the same portrait canvas.
        val rotated = carPlayCanvas(2246, 1080, xdpi = 403.411f, fallbackDpi = 440, refreshRate = 60f)
        // Landscape: 2246 / 403.411 x 25.4 = 141.41 mm.
        val landscape =
            carPlayCanvas(1080, 2246, 403.411f, 440, 60f, orientation = CarPlayOrientation.LANDSCAPE)

        assertThat(rotated.widthPixels to rotated.heightPixels).isEqualTo(1080 to 2246)
        assertThat(landscape.widthPixels to landscape.heightPixels).isEqualTo(2246 to 1080)
        assertThat(landscape.widthMillimetres).isEqualTo(141)
    }

    @Test
    fun `odd sides give up their last pixel, as DiPlay's decoders need even sides`() {
        val config = carPlayCanvas(1081, 2249, xdpi = 400f, fallbackDpi = 440, refreshRate = 60f)

        assertThat(config.widthPixels to config.heightPixels).isEqualTo(1080 to 2248)
    }

    @Test
    fun `a screen above DiPlay's 3840 x 2160 canvas is scaled down whole, its millimetres stay the screen's`() {
        // 2160 x 4320: long side 4320 / 3840 = 1.125, so 1920 x 3840. 2160 / 500 x 25.4 = 109.73 mm.
        val config = carPlayCanvas(2160, 4320, xdpi = 500f, fallbackDpi = 440, refreshRate = 60f)

        assertThat(config.widthPixels to config.heightPixels).isEqualTo(1920 to 3840)
        assertThat(config.widthMillimetres).isEqualTo(110)
    }

    @Test
    fun `a missing physical density falls back to the logical one`() {
        // xdpi 0: 1080 / 440 x 25.4 = 62.35 mm. xdpi 99 is below any real phone screen too.
        assertThat(carPlayCanvas(1080, 2246, 0f, 440, 60f).widthMillimetres).isEqualTo(62)
        assertThat(carPlayCanvas(1080, 2246, 99f, 440, 60f).widthMillimetres).isEqualTo(62)
        assertThat(carPlayCanvas(1080, 2246, 100f, 440, 60f).widthMillimetres).isEqualTo(274)
    }

    @Test
    fun `the frame rate follows the display, within CarPlay's 30 to 60`() {
        assertThat(carPlayCanvas(1080, 2246, 403f, 440, 120f).maxFps).isEqualTo(60)
        assertThat(carPlayCanvas(1080, 2246, 403f, 440, 59.94f).maxFps).isEqualTo(59)
        assertThat(carPlayCanvas(1080, 2246, 403f, 440, 24f).maxFps).isEqualTo(30)
    }

    @Test
    fun `the name, model and maker stay as configured`() {
        val config = carPlayCanvas(1080, 2246, 403f, 440, 60f, base = CarPlayConfig(name = "CB350"))

        assertThat(config.name).isEqualTo("CB350")
    }
}

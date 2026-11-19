package com.abid.handlebarlink.carplay

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.CarPlayOrientation.LANDSCAPE
import com.abid.handlebarlink.carplay.CarPlayOrientation.PORTRAIT
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import org.junit.Test

/**
 * The canvas told to the iPhone. Expected values are worked out by hand from the rules (DiPlay's even sides and 4K
 * canvas limit, millimetres = pixels / dpi x 25.4, the cutout strip left out in landscape), never taken from the
 * function.
 */
class CarPlayCanvasTest {
    @Test
    fun `landscape is the default and puts the Poco's long side across`() {
        // Poco F1: 1080 x 2246 at 403.411 dpi, 60 Hz. 2246 / 403.411 x 25.4 = 141.41 mm.
        val config = carPlayCanvas(1080, 2246, 403.411f, 440, 60f, CarPlayOrientation.DEFAULT)

        assertThat(CarPlayOrientation.DEFAULT).isEqualTo(LANDSCAPE)
        assertThat(config).isEqualTo(
            CarPlayConfig(widthPixels = 2246, heightPixels = 1080, widthMillimetres = 141, maxFps = 60),
        )
        assertThat(config.orientation).isEqualTo(LANDSCAPE)
    }

    @Test
    fun `portrait reports the whole screen upright and its physical width`() {
        // 1080 / 403.411 x 25.4 = 68.00 mm.
        val config = carPlayCanvas(1080, 2246, xdpi = 403.411f, fallbackDpi = 440, refreshRate = 60f, PORTRAIT)

        assertThat(config).isEqualTo(
            CarPlayConfig(widthPixels = 1080, heightPixels = 2246, widthMillimetres = 68, maxFps = 60),
        )
        assertThat(config.orientation).isEqualTo(PORTRAIT)
    }

    @Test
    fun `the window's rotation when measured does not matter`() {
        val measuredOnItsSide = carPlayCanvas(2246, 1080, 403.411f, 440, 60f, PORTRAIT)
        val landscape = carPlayCanvas(2246, 1080, 403.411f, 440, 60f, LANDSCAPE)

        assertThat(measuredOnItsSide.widthPixels to measuredOnItsSide.heightPixels).isEqualTo(1080 to 2246)
        assertThat(landscape.widthPixels to landscape.heightPixels).isEqualTo(2246 to 1080)
    }

    @Test
    fun `landscape leaves the cutout strip out, recomputing the millimetres from the reported width`() {
        // An 89 px notch: 2246 - 89 = 2157, made even 2156; 2157 / 403.411 x 25.4 = 135.81 mm.
        val config = carPlayCanvas(1080, 2246, 403.411f, 440, 60f, LANDSCAPE, cutout = 89)

        assertThat(config.widthPixels to config.heightPixels).isEqualTo(2156 to 1080)
        assertThat(config.widthMillimetres).isEqualTo(136)
    }

    @Test
    fun `portrait keeps the cutout, edge to edge`() {
        val config = carPlayCanvas(1080, 2246, 403.411f, 440, 60f, PORTRAIT, cutout = 89)

        assertThat(config.widthPixels to config.heightPixels).isEqualTo(1080 to 2246)
    }

    @Test
    fun `a cutout report never makes the landscape canvas taller than wide, nor wider than the screen`() {
        // At most 2246 - 1080 = 1166 px is left out, exactly the boundary; a negative report leaves nothing out.
        assertThat(carPlayCanvas(1080, 2246, 403.411f, 440, 60f, LANDSCAPE, cutout = 1166).widthPixels).isEqualTo(1080)
        assertThat(carPlayCanvas(1080, 2246, 403.411f, 440, 60f, LANDSCAPE, cutout = 5000).widthPixels).isEqualTo(1080)
        assertThat(carPlayCanvas(1080, 2246, 403.411f, 440, 60f, LANDSCAPE, cutout = -40).widthPixels).isEqualTo(2246)
    }

    @Test
    fun `the cutout's depth is read from the short edges in either rotation`() {
        // A top notch of 89 px: upright it is the top inset, on its side the left (or right) one.
        assertThat(cutoutOnShortEdges(1080, 2246, left = 0, top = 89, right = 0, bottom = 0)).isEqualTo(89)
        assertThat(cutoutOnShortEdges(2246, 1080, left = 89, top = 0, right = 0, bottom = 0)).isEqualTo(89)
        assertThat(cutoutOnShortEdges(2246, 1080, left = 0, top = 30, right = 89, bottom = 30)).isEqualTo(89)
        assertThat(cutoutOnShortEdges(1080, 2246, left = 30, top = 0, right = 30, bottom = 0)).isEqualTo(0)
    }

    @Test
    fun `odd sides give up their last pixel, as DiPlay's decoders need even sides`() {
        val config = carPlayCanvas(1081, 2249, xdpi = 400f, fallbackDpi = 440, refreshRate = 60f, PORTRAIT)

        assertThat(config.widthPixels to config.heightPixels).isEqualTo(1080 to 2248)
    }

    @Test
    fun `a screen above DiPlay's 3840 x 2160 canvas is scaled down whole, in either orientation`() {
        // 2160 x 4320: long side 4320 / 3840 = 1.125, so 1920 x 3840. 2160 / 500 x 25.4 = 109.73 mm (portrait), and
        // 4320 / 500 x 25.4 = 219.46 mm (landscape).
        val portrait = carPlayCanvas(2160, 4320, xdpi = 500f, fallbackDpi = 440, refreshRate = 60f, PORTRAIT)
        val landscape = carPlayCanvas(2160, 4320, xdpi = 500f, fallbackDpi = 440, refreshRate = 60f, LANDSCAPE)

        assertThat(portrait.widthPixels to portrait.heightPixels).isEqualTo(1920 to 3840)
        assertThat(portrait.widthMillimetres).isEqualTo(110)
        assertThat(landscape.widthPixels to landscape.heightPixels).isEqualTo(3840 to 1920)
        assertThat(landscape.widthMillimetres).isEqualTo(219)
    }

    @Test
    fun `a missing physical density falls back to the logical one`() {
        // xdpi 0: 1080 / 440 x 25.4 = 62.35 mm. xdpi 99 is below any real phone screen too.
        assertThat(carPlayCanvas(1080, 2246, 0f, 440, 60f, PORTRAIT).widthMillimetres).isEqualTo(62)
        assertThat(carPlayCanvas(1080, 2246, 99f, 440, 60f, PORTRAIT).widthMillimetres).isEqualTo(62)
        assertThat(carPlayCanvas(1080, 2246, 100f, 440, 60f, PORTRAIT).widthMillimetres).isEqualTo(274)
    }

    @Test
    fun `the frame rate follows the display, within CarPlay's 30 to 60`() {
        assertThat(carPlayCanvas(1080, 2246, 403f, 440, 120f, LANDSCAPE).maxFps).isEqualTo(60)
        assertThat(carPlayCanvas(1080, 2246, 403f, 440, 59.94f, LANDSCAPE).maxFps).isEqualTo(59)
        assertThat(carPlayCanvas(1080, 2246, 403f, 440, 24f, LANDSCAPE).maxFps).isEqualTo(30)
    }

    @Test
    fun `the name, model and maker stay as configured`() {
        val config = carPlayCanvas(1080, 2246, 403f, 440, 60f, LANDSCAPE, base = CarPlayConfig(name = "CB350"))

        assertThat(config.name).isEqualTo("CB350")
    }
}

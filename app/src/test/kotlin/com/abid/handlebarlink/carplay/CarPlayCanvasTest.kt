package com.abid.handlebarlink.carplay

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.CarPlayOrientation.LANDSCAPE
import com.abid.handlebarlink.carplay.CarPlayOrientation.PORTRAIT
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import org.junit.Test

/**
 * The canvas told to the iPhone, against `diplay_display.py` (carplay/engine `src/test/resources/display/`), a
 * transcription of DiPlay's negotiation in Python. Every expected value is that script's printed output (`python3 -I
 * diplay_display.py`, or its functions run on the inputs named), pasted, never taken from the function under test.
 * The Poco F1's screen: 1080 x 2246, 60 Hz; "takes" is the screen decoder's answer per square side (the Poco's
 * c2.qti.avc.decoder lists sides up to 2176, CarLink's log `square 1920 ... configuredByDecoder=false`).
 */
class CarPlayCanvasTest {
    private val poco: (Int) -> Boolean = { it <= QUALCOMM_LISTED_SIDE }

    private val square =
        CarPlayConfig(
            widthPixels = 1920,
            heightPixels = 1920,
            widthMillimetres = 300,
            heightMillimetres = 300,
            maxFps = 60,
            viewAreas = listOf(CarPlayViewArea(1920, 922), CarPlayViewArea(922, 1920)),
            initialViewArea = 0,
        )

    @Test
    fun `the Poco in landscape gets DiPlay's 1920 square at 300 mm, starting on the landscape area`() {
        // poco-landscape window=2246x1080 canvas=1920x1920 mm=300x300 areas=[(1920, 922), (922, 1920)] initial=0
        val config = carPlayCanvas(1080, 2246, 60f, CarPlayOrientation.DEFAULT, decoderTakes = poco)

        assertThat(config).isEqualTo(square)
        assertThat(config.orientation).isEqualTo(LANDSCAPE)
        assertThat(carPlayWindow(1080, 2246, LANDSCAPE)).isEqualTo(WindowSize(2246, 1080))
    }

    @Test
    fun `the Poco in portrait gets the same square, starting on the portrait area`() {
        // poco-portrait window=1080x2246 canvas=1920x1920 mm=300x300 areas=[(1920, 922), (922, 1920)] initial=1
        val config = carPlayCanvas(2246, 1080, 60f, PORTRAIT, decoderTakes = poco)

        assertThat(config).isEqualTo(square.copy(initialViewArea = 1))
        assertThat(config.orientation).isEqualTo(PORTRAIT)
        assertThat(carPlayWindow(2246, 1080, PORTRAIT)).isEqualTo(WindowSize(1080, 2246))
    }

    @Test
    fun `a cutout strip shortens the landscape window and its area, and in portrait the long side's millimetres`() {
        // poco-landscape-cutout89 window=2157x1080 mm=300x300 areas=[(1920, 960), (922, 1920)] initial=0
        // poco-portrait-cutout89 window=1080x2246 mm=312x312 areas=[(1920, 960), (922, 1920)] initial=1
        val areas = listOf(CarPlayViewArea(1920, 960), CarPlayViewArea(922, 1920))

        assertThat(carPlayCanvas(1080, 2246, 60f, LANDSCAPE, cutout = 89, decoderTakes = poco))
            .isEqualTo(square.copy(viewAreas = areas))
        assertThat(carPlayCanvas(1080, 2246, 60f, PORTRAIT, cutout = 89, decoderTakes = poco))
            .isEqualTo(
                square.copy(widthMillimetres = 312, heightMillimetres = 312, viewAreas = areas, initialViewArea = 1),
            )
        assertThat(carPlayWindow(1080, 2246, LANDSCAPE, cutout = 89)).isEqualTo(WindowSize(2157, 1080))
    }

    @Test
    fun `without a square the decoder takes, DiPlay's plain canvas is the window with no areas`() {
        // poco-landscape-plain window=2246x1080 canvas=2246x1080 mm=300x144 areas=None initial=0
        val config = carPlayCanvas(1080, 2246, 60f, LANDSCAPE, decoderTakes = { false })

        assertThat(config).isEqualTo(
            CarPlayConfig(widthPixels = 2246, heightPixels = 1080, widthMillimetres = 300, heightMillimetres = 144),
        )
        assertThat(config.startViewArea).isEqualTo(CarPlayViewArea(2246, 1080))
        assertThat(config.orientation).isEqualTo(LANDSCAPE)
    }

    @Test
    fun `a decoder that takes only smaller squares gets the largest of DiPlay's sides it takes`() {
        // poco-landscape-1600 canvas=1600x1600 mm=300x300 areas=[(1600, 768), (768, 1600)] initial=0
        val config = carPlayCanvas(1080, 2246, 60f, LANDSCAPE, decoderTakes = { it <= 1600 })

        assertThat(config).isEqualTo(
            square.copy(
                widthPixels = 1600,
                heightPixels = 1600,
                viewAreas = listOf(CarPlayViewArea(1600, 768), CarPlayViewArea(768, 1600)),
            ),
        )
    }

    @Test
    fun `the square's side is the long side capped at 1920 and made even, then DiPlay's smaller sides`() {
        // square_side(1500, s <= 1280) = 1280, (1281, any) = 1280, (2246, s == 1600) = 1600, (1000, none) = None,
        // (2246, s in 1920 or 1600) = 1920.
        assertThat(squareSide(1500) { it <= 1280 }).isEqualTo(1280)
        assertThat(squareSide(1281) { true }).isEqualTo(1280)
        assertThat(squareSide(2246) { it == 1600 }).isEqualTo(1600)
        assertThat(squareSide(1000) { false }).isNull()
        assertThat(squareSide(2246) { it == 1920 || it == 1600 }).isEqualTo(1920)
    }

    @Test
    fun `a cutout report never makes the landscape window taller than wide, and a negative one leaves nothing out`() {
        // negotiate(2246, 1080, cutout 1166): window 1080x1080, canvas 1080, areas [(1080, 1080), (518, 1080)].
        // negotiate(2246, 1080, cutout -40): the plain Poco landscape result.
        assertThat(carPlayCanvas(1080, 2246, 60f, LANDSCAPE, cutout = 1166, decoderTakes = poco)).isEqualTo(
            square.copy(
                widthPixels = 1080,
                heightPixels = 1080,
                viewAreas = listOf(CarPlayViewArea(1080, 1080), CarPlayViewArea(518, 1080)),
            ),
        )
        assertThat(carPlayWindow(1080, 2246, LANDSCAPE, cutout = 5000)).isEqualTo(WindowSize(1080, 1080))
        assertThat(carPlayCanvas(1080, 2246, 60f, LANDSCAPE, cutout = -40, decoderTakes = poco)).isEqualTo(square)
    }

    @Test
    fun `odd sides give up their last pixel, as DiPlay's decoders need even sides`() {
        // negotiate(2249, 1081, portrait, no square): canvas 1080x2248, mm 144x300.
        val config = carPlayCanvas(1081, 2249, 60f, PORTRAIT, decoderTakes = { false })

        assertThat(config.widthPixels to config.heightPixels).isEqualTo(1080 to 2248)
        assertThat(config.widthMillimetres to config.heightMillimetres).isEqualTo(144 to 300)
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
    fun `cutouts on both short edges add up, as the display pads by both`() {
        // A notch and a bottom cutout: the landscape picture pads by WindowInsets.displayCutout on both sides
        // (CarPlayDisplay), so the window loses both strips, 89 + 40, in either rotation.
        assertThat(cutoutOnShortEdges(1080, 2246, left = 0, top = 89, right = 0, bottom = 40)).isEqualTo(129)
        assertThat(cutoutOnShortEdges(2246, 1080, left = 89, top = 0, right = 40, bottom = 0)).isEqualTo(129)
        assertThat(cutoutOnShortEdges(2246, 1080, left = 40, top = 30, right = 89, bottom = 30)).isEqualTo(129)
        // negotiate(2246, 1080, cutout 129, landscape) in diplay_display.py: window 2117x1080, areas
        // [(1920, 978), (922, 1920)].
        assertThat(carPlayWindow(1080, 2246, LANDSCAPE, cutout = 129)).isEqualTo(WindowSize(2117, 1080))
        assertThat(carPlayCanvas(1080, 2246, 60f, LANDSCAPE, cutout = 129, decoderTakes = poco).viewAreas)
            .isEqualTo(listOf(CarPlayViewArea(1920, 978), CarPlayViewArea(922, 1920)))
    }

    @Test
    fun `the frame rate follows the display, within CarPlay's 30 to 60`() {
        assertThat(carPlayCanvas(1080, 2246, 120f, LANDSCAPE, decoderTakes = poco).maxFps).isEqualTo(60)
        assertThat(carPlayCanvas(1080, 2246, 59.94f, LANDSCAPE, decoderTakes = poco).maxFps).isEqualTo(59)
        assertThat(carPlayCanvas(1080, 2246, 24f, LANDSCAPE, decoderTakes = poco).maxFps).isEqualTo(30)
    }

    @Test
    fun `the name, model and maker stay as configured`() {
        val config =
            carPlayCanvas(1080, 2246, 60f, LANDSCAPE, decoderTakes = poco, base = CarPlayConfig(name = "CB350"))

        assertThat(config.name).isEqualTo("CB350")
    }

    private companion object {
        /** c2.qti.avc.decoder's listed height limit (DiPlay CarPlayRotation.kt:69, "lists 4096x2176 at most"). */
        const val QUALCOMM_LISTED_SIDE = 2176
    }
}

package com.abid.handlebarlink.carplay.engine.session

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayInsets
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import com.abid.handlebarlink.carplay.protocol.plist.BinaryPlistWriter
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import org.junit.Test

/**
 * The main display `/info` declares, byte for byte against the `.bplist` files in `src/test/resources/display/`:
 * Python 3.15's
 * plistlib (`plistlib.dumps(display, fmt=FMT_BINARY, sort_keys=False)`) of the dictionary that `diplay_display.py`, a
 * transcription of DiPlay's negotiation and its `displayEntry`/`areaDict` (AirPlayInfoPlist.kt:201-286), builds for the
 * Poco F1. The configs are that script's printed values (`python3 -I diplay_display.py`), pasted, never computed here
 * (docs/LESSONS.md 66, 79).
 */
class MainDisplayTest {
    private fun display(config: CarPlayConfig): ByteArray {
        val info = AirPlayReceiver(config, "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF", "pi", "00").info()
        return BinaryPlistWriter.write(checkNotNull(info.array("displays")).single())
    }

    private fun golden(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/display/$name.bplist")).use { it.readBytes() }

    @Test
    fun `the Poco in landscape declares DiPlay's square with both screens and starts on the landscape one`() {
        // diplay_display.py: poco-landscape canvas=1920x1920 mm=300x300 areas=[(1920, 922), (922, 1920)] initial=0.
        assertThat(display(POCO_LANDSCAPE).toHex()).isEqualTo(golden("poco-landscape").toHex())
    }

    @Test
    fun `the Poco in portrait declares the same square and starts on the portrait screen`() {
        // diplay_display.py: poco-portrait canvas=1920x1920 mm=300x300 areas=[(1920, 922), (922, 1920)] initial=1.
        assertThat(display(POCO_LANDSCAPE.copy(initialViewArea = 1)).toHex()).isEqualTo(golden("poco-portrait").toHex())
    }

    @Test
    fun `a cutout strip makes the landscape area shorter, as the window it fills`() {
        // diplay_display.py: poco-landscape-cutout89 areas=[(1920, 960), (922, 1920)] initial=0.
        val config =
            POCO_LANDSCAPE.copy(viewAreas = listOf(CarPlayViewArea(1920, 960), CarPlayViewArea(922, 1920)))
        assertThat(display(config).toHex()).isEqualTo(golden("poco-landscape-cutout89").toHex())
    }

    @Test
    fun `a plain canvas declares itself as its one area, with no transition control`() {
        // diplay_display.py: poco-landscape-plain canvas=2246x1080 mm=300x144 areas=None initial=0.
        val config =
            CarPlayConfig(
                widthPixels = 2246,
                heightPixels = 1080,
                widthMillimetres = 300,
                heightMillimetres = 144,
                maxFps = 60,
            )
        assertThat(display(config).toHex()).isEqualTo(golden("poco-landscape-plain").toHex())
        val decoded = AirPlayReceiver(config, "id", "id", "pi", "00").info().array("displays")!!.single() as Plist.Dict
        assertThat(decoded["viewAreaTransitionControl"]).isNull()
    }

    @Test
    fun `a smaller square the decoder takes keeps the physical size`() {
        // diplay_display.py: poco-landscape-1600 canvas=1600x1600 mm=300x300 areas=[(1600, 768), (768, 1600)].
        val config =
            POCO_LANDSCAPE.copy(
                widthPixels = 1600,
                heightPixels = 1600,
                viewAreas = listOf(CarPlayViewArea(1600, 768), CarPlayViewArea(768, 1600)),
            )
        assertThat(display(config).toHex()).isEqualTo(golden("poco-landscape-1600").toHex())
    }

    @Test
    fun `maxFPS is the frame rate snapped to the nearest step of 5`() {
        // diplay_display.py snap_fps (AirPlayDisplaySettings.kt:89-92): 52 -> 50, 53 -> 55, 57 -> 55, 58 -> 60.
        val reported =
            listOf(52, 53, 57, 58).map { fps ->
                val info = AirPlayReceiver(CarPlayConfig(maxFps = fps), "id", "id", "pi", "00").info()
                (checkNotNull(info.array("displays")).single() as Plist.Dict).long("maxFPS")
            }
        assertThat(reported).isEqualTo(listOf(50L, 55L, 55L, 60L))
    }

    @Test
    fun `each area's safe insets shrink its safe area, which still lets CarPlay draw outside it`() {
        // diplay_display.py (ours, after DiPlay): poco-safe-landscape areas=[(1920, 960), (922, 1920)]
        // insets=[(28, 28, 28, 28), (27, 74, 27, 54)] initial=0; poco-safe-portrait the same with initial=1, 312 mm.
        val config =
            POCO_LANDSCAPE.copy(
                viewAreas =
                    listOf(
                        CarPlayViewArea(1920, 960, safe = CarPlayInsets(28, 28, 28, 28)),
                        CarPlayViewArea(922, 1920, safe = CarPlayInsets(27, 74, 27, 54)),
                    ),
            )
        assertThat(display(config).toHex()).isEqualTo(golden("poco-safe-landscape").toHex())
        val portrait = config.copy(widthMillimetres = 312, heightMillimetres = 312, initialViewArea = 1)
        assertThat(display(portrait).toHex()).isEqualTo(golden("poco-safe-portrait").toHex())
    }

    @Test
    fun `rightHandDrive tells the iPhone the driver's side, off unless configured, as DiPlay`() {
        // DiPlay AirPlayInfoPlist.kt:46: "rightHandDrive" to config.rightHandDrive (a boolean, default false).
        fun rightHandDrive(config: CarPlayConfig) =
            AirPlayReceiver(config, "id", "id", "pi", "00").info()["rightHandDrive"]

        assertThat(rightHandDrive(CarPlayConfig())).isEqualTo(Plist.Bool(false))
        assertThat(rightHandDrive(CarPlayConfig(rightHandDrive = true))).isEqualTo(Plist.Bool(true))
    }

    @Test
    fun `safe insets that leave no room are refused`() {
        val wide = CarPlayViewArea(1920, 960, safe = CarPlayInsets(left = 960, right = 960))
        assertThat(runCatching { POCO_LANDSCAPE.copy(viewAreas = listOf(wide)) }.isFailure).isEqualTo(true)
        val negative = CarPlayViewArea(1920, 960, safe = CarPlayInsets(top = -1))
        assertThat(runCatching { POCO_LANDSCAPE.copy(viewAreas = listOf(negative)) }.isFailure).isEqualTo(true)
    }

    private companion object {
        val POCO_LANDSCAPE =
            CarPlayConfig(
                widthPixels = 1920,
                heightPixels = 1920,
                widthMillimetres = 300,
                heightMillimetres = 300,
                maxFps = 60,
                viewAreas = listOf(CarPlayViewArea(1920, 922), CarPlayViewArea(922, 1920)),
                initialViewArea = 0,
            )

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

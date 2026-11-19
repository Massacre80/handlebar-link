package com.abid.handlebarlink.carplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which way CarPlay's picture stands on the phone: the rider's "CarPlay screen" setting ([CarPlaySettings]). */
enum class CarPlayOrientation {
    /** On its side, the long side across: the phone across the handlebar (the default, owner 2026-11-19). */
    LANDSCAPE,

    /** Upright, the long side vertical. */
    PORTRAIT,
    ;

    companion object {
        /** Until the rider chooses: landscape (owner, 2026-11-19). */
        val DEFAULT: CarPlayOrientation = LANDSCAPE
    }
}

/** The orientation of [this] canvas: landscape when it is wider than tall. */
val CarPlayConfig.orientation: CarPlayOrientation
    get() = if (widthPixels > heightPixels) CarPlayOrientation.LANDSCAPE else CarPlayOrientation.PORTRAIT

/**
 * The canvas told to the iPhone for a screen of [screenWidth] x [screenHeight] pixels (the largest window, any
 * rotation) with [xdpi] physical pixels per inch along its width and a [refreshRate] in Hz, in [orientation].
 *
 * - Pixels: the screen's short side across in portrait, the long side across in landscape. In landscape the
 *   [cutout] strip is left out of the long side: [cutout] is the depth of the display cutout on the screen's short
 *   edges ([cutoutOnShortEdges]), where the landscape picture would otherwise reach under the notch (docs/PLAN.md
 *   "CarPlay screen", the notch decision). Portrait keeps the whole screen, edge to edge (coordinator, C4). Each side
 *   is made even, as DiPlay's video decoders need (CarPlayHostActivity.evenDisplaySize); sides above DiPlay's canvas
 *   limit (3840 x 2160, CarPlayHostActivity.largerCanvasSupport) are scaled down together.
 * - Physical width: the canvas width over [xdpi] (the unscaled pixels across, without the cutout), in millimetres,
 *   clamped to DiPlay's reported range 1..2000 (AirPlayDisplaySettings.sanitizeReportedPhysicalMm); the iPhone sizes
 *   its controls from it. A density outside [MIN_PLAUSIBLE_DPI]..[MAX_PLAUSIBLE_DPI] (some devices report none) falls
 *   back to [fallbackDpi].
 * - Frame rate: the refresh rate, 30..60 (CarPlayConfig).
 */
@Suppress("LongParameterList") // The screen's measured facts, each one input of the rules above.
internal fun carPlayCanvas(
    screenWidth: Int,
    screenHeight: Int,
    xdpi: Float,
    fallbackDpi: Int,
    refreshRate: Float,
    orientation: CarPlayOrientation,
    cutout: Int = 0,
    base: CarPlayConfig = CarPlayConfig(),
): CarPlayConfig {
    val short = min(screenWidth, screenHeight).coerceAtLeast(MIN_SIDE)
    val screenLong = max(screenWidth, screenHeight).coerceAtLeast(MIN_SIDE)
    val long =
        when (orientation) {
            CarPlayOrientation.PORTRAIT -> screenLong

            // A cutout report never makes the landscape picture taller than wide.
            CarPlayOrientation.LANDSCAPE -> screenLong - cutout.coerceIn(0, screenLong - short)
        }
    val scale = min(1.0, min(MAX_LONG_SIDE.toDouble() / long, MAX_SHORT_SIDE.toDouble() / short))
    val evenShort = even(short * scale)
    val evenLong = even(long * scale)
    val (width, height) =
        when (orientation) {
            CarPlayOrientation.PORTRAIT -> evenShort to evenLong
            CarPlayOrientation.LANDSCAPE -> evenLong to evenShort
        }
    // The density is the screen's own, so the physical width is measured on the unscaled side across.
    val across = if (orientation == CarPlayOrientation.PORTRAIT) short else long
    val dpi = if (xdpi in MIN_PLAUSIBLE_DPI..MAX_PLAUSIBLE_DPI) xdpi else fallbackDpi.toFloat()
    val millimetres = (across / dpi * MILLIMETRES_PER_INCH).roundToInt().coerceIn(MIN_MILLIMETRES, MAX_MILLIMETRES)
    val fps = refreshRate.toInt().coerceIn(MIN_FPS, MAX_FPS)
    return base.copy(widthPixels = width, heightPixels = height, widthMillimetres = millimetres, maxFps = fps)
}

/**
 * How deep the display cutout reaches into a [width] x [height] screen from its short edges, from the cutout's safe
 * insets in the same rotation ([left], [top], [right], [bottom]; `Display.getCutout`): the top and bottom insets while
 * the screen stands upright, the left and right ones while it lies on its side. That strip is what a landscape
 * picture would lose under the notch, whichever way the phone is turned.
 */
@Suppress("LongParameterList") // A rectangle and its four insets.
internal fun cutoutOnShortEdges(
    width: Int,
    height: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): Int = if (height >= width) top + bottom else left + right

private fun even(value: Double): Int = (value.toInt() / 2 * 2).coerceAtLeast(MIN_SIDE)

private const val MIN_SIDE = 2

private const val MAX_LONG_SIDE = 3840
private const val MAX_SHORT_SIDE = 2160
private const val MILLIMETRES_PER_INCH = 25.4f
private const val MIN_MILLIMETRES = 1
private const val MAX_MILLIMETRES = 2000
private const val MIN_FPS = 30
private const val MAX_FPS = 60

/** Below this no phone screen is real (a missing value reads 0 or 160). */
internal const val MIN_PLAUSIBLE_DPI = 100f

/** Above this no phone screen is real. */
internal const val MAX_PLAUSIBLE_DPI = 1000f

/**
 * Where the coordinator says which orientation the next CarPlay attempt reports ([CarPlayCanvas]); it is set before
 * each start, so a change reaches the iPhone at the next connection.
 */
fun interface CanvasOrientation {
    /** The next attempts report a canvas in [orientation]. */
    fun use(orientation: CarPlayOrientation)
}

/**
 * Measures this phone's screen for [carPlayCanvas]: the default display's largest window
 * (`WindowManager.getMaximumWindowMetrics` through a window context, the documented way for a non-activity context:
 * Media projection guide, "Media projection size"), its cutout (`Display.getCutout`), physical density
 * (`DisplayMetrics.xdpi`, falling back to `densityDpi`) and refresh rate. The engine calls [config] at the start of
 * each connection attempt on its own thread (createCarPlayLink); the screen is measured once per process, on that first
 * call, never while Hilt builds objects (docs/LESSONS.md 60). The orientation is the one the coordinator last chose
 * ([use]); [reported] is the canvas the last attempt told the iPhone, for the CarPlay display.
 */
@Singleton
class CarPlayCanvas
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : CanvasOrientation {
        private val log = logger.child("carplay")

        private val screen: Lazy<Screen> = lazy(::measure)

        private val chosen = MutableStateFlow(CarPlayOrientation.DEFAULT)

        private val last = MutableStateFlow<CarPlayConfig?>(null)

        /** The canvas the latest attempt told the iPhone; null before the first attempt of this process. */
        val reported: StateFlow<CarPlayConfig?> = last.asStateFlow()

        /** The orientation the next attempt reports. */
        val orientation: StateFlow<CarPlayOrientation> = chosen.asStateFlow()

        override fun use(orientation: CarPlayOrientation) {
            chosen.value = orientation
        }

        /** The screen's measured facts. */
        private class Screen(
            val width: Int,
            val height: Int,
            val cutout: Int,
            val xdpi: Float,
            val densityDpi: Int,
            val refresh: Float,
        )

        private fun measure(): Screen {
            val displays = context.getSystemService(DisplayManager::class.java)
            val display = checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))
            val window = context.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION, null)
            val bounds = window.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val metrics = window.resources.displayMetrics
            val cut = display.cutout
            val cutout =
                if (cut == null) {
                    0
                } else {
                    cutoutOnShortEdges(
                        bounds.width(),
                        bounds.height(),
                        cut.safeInsetLeft,
                        cut.safeInsetTop,
                        cut.safeInsetRight,
                        cut.safeInsetBottom,
                    )
                }
            log.info(
                "carplay.screen",
                "screen" to "${bounds.width()}x${bounds.height()}",
                "cutout" to cutout,
                "xdpi" to metrics.xdpi,
                "density_dpi" to metrics.densityDpi,
                "refresh" to display.refreshRate,
            )
            return Screen(
                bounds.width(),
                bounds.height(),
                cutout,
                metrics.xdpi,
                metrics.densityDpi,
                display.refreshRate,
            )
        }

        /**
         * The configuration for the next connection attempt in the chosen orientation; measures the screen the first
         * time (Binder calls). Logs the canvas when it differs from the last one reported.
         */
        fun config(): CarPlayConfig {
            val screen = screen.value
            val orientation = chosen.value
            val config =
                carPlayCanvas(
                    screen.width,
                    screen.height,
                    screen.xdpi,
                    screen.densityDpi,
                    screen.refresh,
                    orientation,
                    screen.cutout,
                )
            if (last.value != config) {
                log.info(
                    "carplay.canvas",
                    "orientation" to orientation,
                    "width" to config.widthPixels,
                    "height" to config.heightPixels,
                    "width_mm" to config.widthMillimetres,
                    "fps" to config.maxFps,
                )
            }
            last.value = config
            return config
        }
    }

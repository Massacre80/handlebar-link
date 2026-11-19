package com.abid.handlebarlink.carplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which way CarPlay's picture stands on the phone. */
enum class CarPlayOrientation {
    /** Upright, the long side vertical: the phone on the handlebar (the default, coordinator 2026-11-19). */
    PORTRAIT,

    /** On its side, the long side horizontal. */
    LANDSCAPE,
}

/**
 * The orientation of the canvas reported to the iPhone ([carPlayCanvas]); the one place to change it. The CarPlay
 * display (ui/carplay/CarPlayDisplay) keeps the canvas's shape whichever way the phone is turned.
 */
val CARPLAY_ORIENTATION: CarPlayOrientation = CarPlayOrientation.PORTRAIT

/**
 * The canvas told to the iPhone for a screen of [screenWidth] x [screenHeight] pixels (the largest window, any
 * rotation) with [xdpi] physical pixels per inch along its width and a [refreshRate] in Hz: the whole screen, edge to
 * edge, in [orientation].
 *
 * - Pixels: the screen's short side across in portrait (long side across in landscape), each side made even, as
 *   DiPlay's video decoders need (CarPlayHostActivity.evenDisplaySize); sides above DiPlay's canvas limit (3840 x 2160,
 *   CarPlayHostActivity.largerCanvasSupport) are scaled down together.
 * - Physical width: the canvas width over [xdpi], in millimetres, clamped to DiPlay's reported range 1..2000
 *   (AirPlayDisplaySettings.sanitizeReportedPhysicalMm); the iPhone sizes its controls from it. A density outside
 *   [MIN_PLAUSIBLE_DPI]..[MAX_PLAUSIBLE_DPI] (some devices report none) falls back to [fallbackDpi].
 * - Frame rate: the refresh rate, 30..60 (CarPlayConfig).
 */
internal fun carPlayCanvas(
    screenWidth: Int,
    screenHeight: Int,
    xdpi: Float,
    fallbackDpi: Int,
    refreshRate: Float,
    orientation: CarPlayOrientation = CARPLAY_ORIENTATION,
    base: CarPlayConfig = CarPlayConfig(),
): CarPlayConfig {
    val short = min(screenWidth, screenHeight).coerceAtLeast(MIN_SIDE)
    val long = max(screenWidth, screenHeight).coerceAtLeast(MIN_SIDE)
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
 * Measures this phone's screen for [carPlayCanvas]: the default display's largest window
 * (`WindowManager.getMaximumWindowMetrics` through a window context, the documented way for a non-activity context:
 * Media projection guide, "Media projection size"), its physical density (`DisplayMetrics.xdpi`, falling back to
 * `densityDpi`) and refresh rate. The engine calls [config] at the start of each connection attempt on its own thread
 * (createCarPlayLink); the screen is measured once per process, on that first call, never while Hilt builds objects
 * (docs/LESSONS.md 60).
 */
@Singleton
class CarPlayCanvas
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) {
        private val log = logger.child("carplay")

        private val measured: Lazy<CarPlayConfig> = lazy(::measure)

        private fun measure(): CarPlayConfig {
            val displays = context.getSystemService(DisplayManager::class.java)
            val display = checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))
            val window = context.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION, null)
            val bounds = window.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val metrics = window.resources.displayMetrics
            val config =
                carPlayCanvas(bounds.width(), bounds.height(), metrics.xdpi, metrics.densityDpi, display.refreshRate)
            log.info(
                "carplay.canvas",
                "screen" to "${bounds.width()}x${bounds.height()}",
                "xdpi" to metrics.xdpi,
                "density_dpi" to metrics.densityDpi,
                "refresh" to display.refreshRate,
                "orientation" to CARPLAY_ORIENTATION,
                "width" to config.widthPixels,
                "height" to config.heightPixels,
                "width_mm" to config.widthMillimetres,
                "fps" to config.maxFps,
            )
            return config
        }

        /** The configuration for the next connection attempt; measures the screen the first time (Binder calls). */
        fun config(): CarPlayConfig = measured.value

        /** The configuration once [config] has measured it, else null; never measures (safe on the main thread). */
        fun measuredOrNull(): CarPlayConfig? = if (measured.isInitialized()) measured.value else null
    }

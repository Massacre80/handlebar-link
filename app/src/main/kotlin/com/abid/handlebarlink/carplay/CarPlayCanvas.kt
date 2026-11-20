package com.abid.handlebarlink.carplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayInsets
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import com.abid.handlebarlink.carplay.engine.android.screenDecoderTakes
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

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

/**
 * The orientation of [this] canvas's picture: of the area CarPlay starts in ([CarPlayConfig.startViewArea]), landscape
 * when it is wider than tall. A turning screen's square holds both; the start area says which one shows.
 */
val CarPlayConfig.orientation: CarPlayOrientation
    get() =
        startViewArea.let { if (it.width > it.height) CarPlayOrientation.LANDSCAPE else CarPlayOrientation.PORTRAIT }

/** A window of [width] x [height] pixels. */
data class WindowSize(
    val width: Int,
    val height: Int,
)

/**
 * CarPlay's window on a screen of [screenWidth] x [screenHeight] pixels (any rotation) in [orientation]: the display's
 * picture box. Landscape: the long side across less the [cutout] strip ([cutoutOnShortEdges]; the display pads the
 * landscape picture out of the notch, docs/PLAN.md "CarPlay screen"), never narrower than tall; portrait: the whole
 * screen upright, edge to edge.
 */
internal fun carPlayWindow(
    screenWidth: Int,
    screenHeight: Int,
    orientation: CarPlayOrientation,
    cutout: Int = 0,
): WindowSize {
    val short = min(screenWidth, screenHeight).coerceAtLeast(MIN_SIDE)
    val long = max(screenWidth, screenHeight).coerceAtLeast(MIN_SIDE)
    return when (orientation) {
        CarPlayOrientation.PORTRAIT -> WindowSize(short, long)
        CarPlayOrientation.LANDSCAPE -> WindowSize(long - cutout.coerceIn(0, long - short), short)
    }
}

/**
 * The canvas told to the iPhone, as DiPlay negotiates it for a screen that turns, the square CarLink turns on for a
 * phone (carlink-core CarPlayAdapter.kt:269-272; docs/PLAN.md "CarPlay picture, DiPlay's negotiation"), for a screen of
 * [screenWidth] x [screenHeight] pixels with a [cutout] strip, in [orientation], at [refreshRate] Hz. [decoderTakes]
 * says whether the screen decoder takes a square of that side.
 *
 * 1. CarPlay's window in each orientation ([carPlayWindow]); the start one is [orientation]'s, made even
 *    (CarPlayHostActivity.kt:7281-7282).
 * 2. Physical size: [referenceMm] for the widest window, scaled to the start window
 *    (AirPlayDisplaySettings.resolvePhysicalSizeMm, AirPlayDisplaySettings.kt:44-81, basis WIDTH;
 *    CarPlayHostActivity.kt:5289-5297, 6196-6203). DiPlay's reference is a fixed width, [REFERENCE_WIDTH_MM]
 *    (CarPlaySize.DEFAULT, AirPlayPersistence.kt:833-839), the default here; the app passes the widest window's real
 *    width instead ([realWidthMm]; owner 2026-11-20), so CarPlay's controls come out at their real size on any phone.
 * 3. The square (CarPlayRotation.squareSide, CarPlayRotation.kt:20-23, 39-78): the long side capped at
 *    [SQUARE_LIMIT] (picture "Smoother"), made even, else the next of DiPlay's sides below it, the first the decoder
 *    takes. Both its sides report the long side's physical length (CarPlayHostActivity.kt:5136-5145).
 * 4. Two view areas at the square's top-left corner: the landscape window and the portrait window, each scaled to the
 *    square's side across its long side (CarPlayRotation.turningAreas, CarPlayRotation.kt:88-99;
 *    CarPlayViewAreas.build,
 *    CarPlayViewAreas.kt:178-233); CarPlay starts in [orientation]'s. DiPlay estimates the other window as a screen
 *    whose system bars keep their edges (CarPlayRotation.turnedWindows); here both windows are known, so the cutout
 *    rule replaces that estimate (the same areas when there is no cutout).
 * 5. No square the decoder takes: DiPlay's plain canvas, the start window as the one area
 *    (CarPlayViewAreas.kt:191-192).
 * 6. Frame rate: the refresh rate in 30..60 (CarLink asks 60 of a decoder that carries it, PicturePolicy.kt:131); the
 *    engine snaps it to DiPlay's steps of 5.
 * 7. Safe areas (ours, not DiPlay's: its turning square drops the safe area, CarPlayHostActivity.kt:5124-5145, so
 *    CarLink draws the dock into the corners and under the navigation handle): each area keeps CarPlay's controls
 *    clear of the phone's [edges] in that area's orientation ([safeInsets]); the plain canvas then declares its one
 *    area to carry it.
 */
@Suppress("LongParameterList") // The screen's measured facts and the decoder's answer, each one input of the rules.
internal fun carPlayCanvas(
    screenWidth: Int,
    screenHeight: Int,
    refreshRate: Float,
    orientation: CarPlayOrientation,
    cutout: Int = 0,
    decoderTakes: (side: Int) -> Boolean = { true },
    base: CarPlayConfig = CarPlayConfig(),
    edges: ScreenEdges = ScreenEdges.NONE,
    referenceMm: Int = REFERENCE_WIDTH_MM,
): CarPlayConfig {
    val landscape = carPlayWindow(screenWidth, screenHeight, CarPlayOrientation.LANDSCAPE, cutout)
    val portrait = carPlayWindow(screenWidth, screenHeight, CarPlayOrientation.PORTRAIT, cutout)
    val window = if (orientation == CarPlayOrientation.LANDSCAPE) landscape else portrait
    val width = even(window.width)
    val height = even(window.height)
    // The widest and tallest windows DiPlay has seen: this phone's two windows (and the current one).
    val maximumWidth = maxOf(landscape.width, portrait.width, window.width)
    val widthMm = referenceMm * (width.toDouble() / maximumWidth)
    val heightMm = widthMm * height / width
    val fps = refreshRate.toInt().coerceIn(MIN_FPS, MAX_FPS)
    val side = squareSide(max(width, height), decoderTakes)
    if (side == null) {
        val whole = CarPlayViewArea(width, height)
        val safe = safeInsets(edges, orientation, window, whole)
        return base.copy(
            widthPixels = width,
            heightPixels = height,
            widthMillimetres = reportedMm(widthMm),
            heightMillimetres = reportedMm(heightMm),
            maxFps = fps,
            viewAreas = if (safe == CarPlayInsets.NONE) emptyList() else listOf(whole.copy(safe = safe)),
            initialViewArea = 0,
        )
    }
    val squareMm = if (width >= height) reportedMm(widthMm) else reportedMm(heightMm)
    val wide = CarPlayViewArea(side, evenDown(side.toLong() * landscape.height / landscape.width))
    val tall = CarPlayViewArea(evenDown(side.toLong() * portrait.width / portrait.height), side)
    val areas =
        listOf(
            wide.copy(safe = safeInsets(edges, CarPlayOrientation.LANDSCAPE, landscape, wide)),
            tall.copy(safe = safeInsets(edges, CarPlayOrientation.PORTRAIT, portrait, tall)),
        )
    return base.copy(
        widthPixels = side,
        heightPixels = side,
        widthMillimetres = squareMm,
        heightMillimetres = squareMm,
        maxFps = fps,
        viewAreas = areas,
        initialViewArea = if (window.height > window.width) PORTRAIT_AREA else LANDSCAPE_AREA,
    )
}

/**
 * DiPlay's square side for a [longSide] (CarPlayRotation.squareSide, CarPlayRotation.kt:64-74): the long side capped
 * at [SQUARE_LIMIT] and made even, then DiPlay's smaller sides; the first [takes] accepts, or null.
 */
internal fun squareSide(
    longSide: Int,
    takes: (side: Int) -> Boolean,
): Int? {
    val limit = min(SQUARE_LIMIT, longSide) and 1.inv()
    return (listOf(limit) + SQUARE_SIDES.filter { it < limit }).firstOrNull { it >= MIN_SIDE && takes(it) }
}

/** Java's `Math.round`, then DiPlay's reported range (AirPlayDisplaySettings.kt:41-42, 77-80). */
private fun reportedMm(value: Double): Int = Math.round(value).toInt().coerceIn(MIN_MILLIMETRES, MAX_MILLIMETRES)

private fun evenDown(value: Long): Int = value.toInt() and 1.inv()

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

private fun even(value: Int): Int = (value / 2 * 2).coerceAtLeast(MIN_SIDE)

private const val MIN_SIDE = 2

/**
 * CarPlay's dock on the right, the side the owner wants it on the bike (2026-11-20): told as a right-hand-drive car
 * ([CarPlayConfig.rightHandDrive]), as cars in India are.
 */
internal const val DOCK_ON_THE_RIGHT = true

/** DiPlay's default "CarPlay size", Medium: the screen width told to the iPhone (CarPlaySize.kt:8-14). */
internal const val REFERENCE_WIDTH_MM = 300

/**
 * The real width in millimetres of a window [pixels] wide on a screen of [xdpi] x [ydpi] (`DisplayMetrics`, the
 * physical pixels per inch) with [densityDpi] as its density bucket: what CarPlay is told, so its controls come out
 * at their real size (owner 2026-11-20). iOS sizes CarPlay's controls physically from the size it is told
 * (CarPlaySize.kt:3-6) and switches layouts at a few sizes (AirPlayDisplaySettings.kt:22-23): on the Poco (2160 px at
 * 403 ppi, 136 mm) the real size docks CarPlay on a side with large icons, where DiPlay's 300 mm gives a thin
 * bottom dock with icons a third smaller (measured on the Poco: 300, 200, 150, 136 and 120 mm).
 * - The lower of the two densities: phones report one of them as the density bucket now and then (the Poco reports
 *   419 for y, 403 for x); the lower one only errs towards a larger size, never under the smallest clean layout.
 * - A density more than twice off the bucket is a broken report: the bucket is used instead.
 * - At least [MIN_REAL_MM]: at 120 mm the iPhone's dock overlapped its clock (Poco, 2026-11-20). At most
 *   [MAX_REAL_MM], DiPlay's largest CarPlay size (AirPlayDisplaySettings.kt:21).
 */
internal fun realWidthMm(
    pixels: Int,
    xdpi: Float,
    ydpi: Float,
    densityDpi: Int,
): Int {
    val lower = min(xdpi, ydpi)
    val dpi = if (lower >= densityDpi / 2f && lower <= densityDpi * 2f) lower else densityDpi.toFloat()
    return Math.round(pixels / dpi * MM_PER_INCH).coerceIn(MIN_REAL_MM, MAX_REAL_MM)
}

/** The smallest width CarPlay is told: 135 mm laid out cleanly on the Poco, 120 mm did not. */
internal const val MIN_REAL_MM = 135

/** DiPlay's largest CarPlay size (AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM). */
internal const val MAX_REAL_MM = 400

private const val MM_PER_INCH = 25.4f

/** The "Smoother" square's cap, DiPlay's default picture (CarPlayRotation.kt:23, 29-30). */
internal const val SQUARE_LIMIT = 1920

/** DiPlay's square sides below the cap (CarPlayRotation.kt:20). */
@Suppress("MagicNumber") // DiPlay's list as it stands, cited above.
private val SQUARE_SIDES = listOf(2560, 2304, 2048, 1920, 1600, 1280)

/** The order of the square's areas: the landscape screen, then the portrait one (CarPlayHostActivity.kt:5155-5158). */
private const val LANDSCAPE_AREA = 0
private const val PORTRAIT_AREA = 1

private const val MIN_MILLIMETRES = 1
private const val MAX_MILLIMETRES = 2000
private const val MIN_FPS = 30
private const val MAX_FPS = 60

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
 * Media projection guide, "Media projection size"), its cutout (`Display.getCutout`) and refresh rate, the parts
 * CarPlay's controls must stay out of (rounded corners, cutout ends, navigation bar: [screenEdges] from that window's
 * insets), and asks the screen decoder which square it takes ([screenDecoderTakes], once per side). The engine calls
 * [config] at the start of each connection attempt on its own thread (createCarPlayLink); the screen is measured once
 * per process, on that first call, never while Hilt builds objects (docs/LESSONS.md 60). The orientation is the one the
 * coordinator last chose ([use]); [reported] is the canvas the last attempt told the iPhone, for the CarPlay display.
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

        /** The decoder's answer per square side ([screenDecoderTakes] asks `MediaCodecList`, a Binder call). */
        private val squares = ConcurrentHashMap<Int, Boolean>()

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
            val refresh: Float,
            val edges: ScreenEdges,
            val realMm: Int,
        )

        private fun measure(): Screen {
            val displays = context.getSystemService(DisplayManager::class.java)
            val display = checkNotNull(displays.getDisplay(Display.DEFAULT_DISPLAY))
            val window = context.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION, null)
            val metrics = window.getSystemService(WindowManager::class.java).maximumWindowMetrics
            val bounds = metrics.bounds
            val edges = screenEdges(metrics.windowInsets, display.rotation)
            val density = window.resources.displayMetrics
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
            // The widest window CarPlay gets: landscape, less the cutout strip (carPlayWindow).
            val widest = carPlayWindow(bounds.width(), bounds.height(), CarPlayOrientation.LANDSCAPE, cutout).width
            val realMm = realWidthMm(widest, density.xdpi, density.ydpi, density.densityDpi)
            log.info(
                "carplay.screen",
                "screen" to "${bounds.width()}x${bounds.height()}",
                "cutout" to cutout,
                "refresh" to display.refreshRate,
                "corner" to edges.cornerRadius,
                "cutoutEnds" to "${edges.cutoutTop}/${edges.cutoutBottom}",
                "navigationBar" to edges.navigationBar,
                "dpi" to "${density.xdpi}x${density.ydpi}/${density.densityDpi}",
                "realMm" to realMm,
            )
            return Screen(bounds.width(), bounds.height(), cutout, display.refreshRate, edges, realMm)
        }

        /**
         * The configuration for the next connection attempt in the chosen orientation; measures the screen the first
         * time (Binder calls). Logs the negotiation at every attempt, as CarLink logs `starting controller`
         * (CarPlayHostActivity.kt:5773-5783): `carplay.picture display=<window> negotiated=<canvas> viewArea=<start
         * area> areas=<count> mm=<physical> fps=<asked>`.
         */
        fun config(): CarPlayConfig {
            val screen = screen.value
            val orientation = chosen.value
            val config =
                carPlayCanvas(
                    screen.width,
                    screen.height,
                    screen.refresh,
                    orientation,
                    screen.cutout,
                    decoderTakes = { side -> squares.getOrPut(side) { screenDecoderTakes(side, side) } },
                    base = CarPlayConfig(rightHandDrive = DOCK_ON_THE_RIGHT),
                    edges = screen.edges,
                    referenceMm = screen.realMm,
                )
            val window = carPlayWindow(screen.width, screen.height, orientation, screen.cutout)
            val area = config.startViewArea
            log.info(
                "carplay.picture",
                "display" to "${window.width}x${window.height}",
                "negotiated" to "${config.widthPixels}x${config.heightPixels}",
                "viewArea" to "${config.initialViewArea}:${area.width}x${area.height}+${area.originX}+${area.originY}",
                "areas" to config.viewAreas.joinToString(",") { "${it.width}x${it.height}" }.ifEmpty { "none" },
                "safe" to area.safe.let { "${it.left},${it.top},${it.right},${it.bottom}" },
                "mm" to "${config.widthMillimetres}x${config.heightMillimetres}",
                "fps" to config.maxFps,
                "orientation" to orientation,
            )
            last.value = config
            return config
        }
    }

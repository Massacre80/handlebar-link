package com.abid.handlebarlink.carplay.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * The wireless CarPlay receiver: brings up a Wi-Fi Direct group, bootstraps the iPhone over Bluetooth
 * iAP2, and serves its AirPlay control connection (pairing, MFi authentication, encryption). The app's
 * coordinator calls [run] for as long as CarPlay may work and steers it with [start] and [stop]
 * (AGENTS.md, "Engine architecture"). A CarPlay failure never touches the bike link.
 *
 * Command methods are non-suspending, thread-safe and may be called before [run]; they are queued.
 */
public interface CarPlayLink {
    /** What the receiver is doing. */
    public val state: StateFlow<CarPlayState>

    /**
     * Whether the app must keep its foreground service running for the receiver. Updated before [state]
     * reports the change that caused it.
     */
    public val keepAlive: StateFlow<Boolean>

    /** Start connecting, and keep reconnecting until [stop]. */
    public fun start()

    /** Disconnect and release the network, Bluetooth and sockets. */
    public fun stop()

    /**
     * The fingers on the CarPlay picture now, one entry per finger of one touch event (DiPlay CarPlayTouchMapper);
     * an empty list lifts every finger. The engine keeps each finger in one of the touchscreen's two report slots
     * from press to release and sends the report to the iPhone as `hidSendReport` on the event channel (DiPlay
     * AirPlaySession.sendTouch, AirPlayHid.touchReport, AirPlayTouchSlots). Touches made while no event channel is
     * connected are dropped, never delivered to a later session.
     */
    public fun touch(contacts: List<TouchContact>)

    /**
     * The iPhone's calls now, from iAP2 CallStateUpdate (0x4155) on the Bluetooth bootstrap link, in the order the
     * iPhone first reported them; disconnected and disconnecting calls are gone (DiPlay `BydCarPlayCall.kt:34-51`).
     * Empty while no attempt has heard of a call, and emptied when the bootstrap link or the attempt ends.
     */
    public val calls: StateFlow<List<CarPlayCall>>

    /**
     * One press of [key]: the key's HID report, then its release, back to back on the event channel as DiPlay's
     * `sendMedia` and `sendTelephony` send them (AirPlaySession.kt:380-388). Presses made while no event channel is
     * connected are dropped, never delivered to a later session.
     */
    public fun press(key: CarPlayKey)

    /**
     * Opens Siri on the iPhone as a car's voice button does: `requestSiri` with siriAction 2, then 3, on the event
     * channel (DiPlay AirPlaySession.invokeSiri, AirPlaySession.kt:390-393). Dropped like [press] when no event
     * channel is connected.
     */
    public fun requestSiri()

    /**
     * Runs the receiver until the calling coroutine is cancelled; everything is closed on the way out.
     * Only one [run] at a time.
     */
    public suspend fun run()
}

/**
 * One finger on the CarPlay picture (DiPlay AirPlayContact): [x] and [y] from 0 (left, top) to 1 (right, bottom) of
 * the whole canvas (not only the view area shown), which the engine scales to the canvas the iPhone was told
 * ([CarPlayConfig.widthPixels], [CarPlayConfig.heightPixels]). Values outside 0..1 are clamped.
 */
public data class TouchContact(
    /** The platform's pointer id, which stays with its finger (`MotionEvent.getPointerId`). */
    val pointerId: Int,
    val x: Double,
    val y: Double,
    /** False when this finger has just lifted. */
    val down: Boolean,
)

/**
 * What the receiver tells the iPhone about itself and the screen. The canvas ([widthPixels] x [heightPixels],
 * [widthMillimetres] x [heightMillimetres], [maxFps], [viewAreas] with [initialViewArea]) goes into `/info` (the main
 * display, DiPlay AirPlayInfoPlist.kt:201-286, and the touchscreen's HID descriptor) and sizes the decoder at the
 * stream SETUP. DiPlay's video decoders need even sides (CarPlayHostActivity.evenDisplaySize).
 */
public data class CarPlayConfig(
    /** Name shown on the iPhone and in its Bonjour browser. */
    val name: String = "Handlebar Link",
    /** Model identifier. */
    val model: String = "HandlebarLink1,1",
    /** Manufacturer. */
    val manufacturer: String = "Handlebar Link",
    /** The CarPlay canvas width in pixels: the size of every picture the iPhone sends. */
    val widthPixels: Int = 1280,
    /** The CarPlay canvas height in pixels. */
    val heightPixels: Int = 720,
    /** Physical width of the canvas in millimetres; smaller values give larger controls. */
    val widthMillimetres: Int = 150,
    /**
     * Physical height of the canvas in millimetres; null derives it from the width and the canvas's shape, as DiPlay
     * does for a display without one (AirPlayInfoPlist.kt:205-210).
     */
    val heightMillimetres: Int? = null,
    /** Highest frame rate to ask for, 30..60. */
    val maxFps: Int = 60,
    /**
     * The parts of the canvas CarPlay may draw in (DiPlay CarPlayViewAreas, AirPlayViewArea); empty declares the whole
     * canvas as the one area. The iPhone draws only in the area in use and leaves the rest of each picture black.
     */
    val viewAreas: List<CarPlayViewArea> = emptyList(),
    /** The area CarPlay starts in, an index into [viewAreas] (0 without areas). */
    val initialViewArea: Int = 0,
) {
    init {
        require(widthPixels > 0 && heightPixels > 0) { "Canvas size must be positive" }
        require(widthMillimetres in 1..MAX_MILLIMETRES) { "Physical width must be 1..$MAX_MILLIMETRES mm" }
        require(heightMillimetres == null || heightMillimetres in 1..MAX_MILLIMETRES) {
            "Physical height must be 1..$MAX_MILLIMETRES mm"
        }
        require(maxFps in MIN_FPS..MAX_FPS) { "Frame rate must be $MIN_FPS..$MAX_FPS" }
        require(widthPixels <= MAX_SIDE && heightPixels <= MAX_SIDE) { "Canvas sides must be at most $MAX_SIDE" }
        require(viewAreas.all { it.fitsIn(widthPixels, heightPixels) }) { "Every view area must lie in the canvas" }
        require(if (viewAreas.isEmpty()) initialViewArea == 0 else initialViewArea in viewAreas.indices) {
            "The initial view area must be one of the view areas"
        }
    }

    /** The area CarPlay draws in when a session starts: [initialViewArea], or the whole canvas without areas. */
    public val startViewArea: CarPlayViewArea
        get() = viewAreas.getOrNull(initialViewArea) ?: CarPlayViewArea(widthPixels, heightPixels)

    /**
     * The frame rate `/info` reports and the decoder is sized for: [maxFps] snapped to the nearest multiple of 5 in
     * 30..60, as DiPlay's `sanitizeFps` does (AirPlayDisplaySettings.kt:30-31, 89-92).
     */
    internal val reportedFps: Int
        get() = ((maxFps - MIN_FPS) + FPS_STEP / 2) / FPS_STEP * FPS_STEP + MIN_FPS

    private companion object {
        /** DiPlay's reported-size clamp (AirPlayDisplaySettings). */
        const val MAX_MILLIMETRES = 2000
        const val MIN_FPS = 30
        const val MAX_FPS = 60

        /** DiPlay AirPlayDisplaySettings.FPS_STEP. */
        const val FPS_STEP = 5

        /** The touchscreen descriptor's logical maxima are 16-bit (AirPlayHid.kt:102-103). */
        const val MAX_SIDE = 0xFFFF
    }
}

/**
 * A rectangle of the canvas CarPlay can draw in (DiPlay AirPlayViewArea, AirPlayConfig.kt:50-56): [width] x [height]
 * pixels with its top-left corner at [originX], [originY]. DiPlay anchors every area of a turning screen's square at
 * the canvas's top-left corner (CarPlayViewAreas.kt:197-200).
 *
 * [safe]: how far in from each of the area's edges CarPlay keeps its controls (the dock, buttons, lists), while its
 * wallpaper and maps still fill the whole area: the area's `safeArea` with `drawUIOutsideSafeArea`
 * (AirPlayInfoPlist.kt:263-283). The phone's own rounded corners, cutout and navigation bar go here, so nothing
 * CarPlay draws to be touched sits under them. None leaves the safe area the whole area (DiPlay's turning square).
 */
public data class CarPlayViewArea(
    val width: Int,
    val height: Int,
    val originX: Int = 0,
    val originY: Int = 0,
    val safe: CarPlayInsets = CarPlayInsets.NONE,
) {
    internal fun fitsIn(
        canvasWidth: Int,
        canvasHeight: Int,
    ): Boolean =
        width > 0 && height > 0 && originX >= 0 && originY >= 0 &&
            originX + width <= canvasWidth && originY + height <= canvasHeight &&
            safe.fitsIn(width, height)
}

/** Distances in pixels in from the [left], [top], [right] and [bottom] edges of a rectangle. */
public data class CarPlayInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    /** Whether these insets leave some of a [width] x [height] rectangle (none is negative). */
    internal fun fitsIn(
        width: Int,
        height: Int,
    ): Boolean = left >= 0 && top >= 0 && right >= 0 && bottom >= 0 && left + right < width && top + bottom < height

    /** The insets everyone needs: none. */
    public companion object {
        /** No insets. */
        public val NONE: CarPlayInsets = CarPlayInsets()
    }
}

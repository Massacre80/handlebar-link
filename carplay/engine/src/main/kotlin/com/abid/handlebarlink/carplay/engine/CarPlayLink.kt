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
     * Runs the receiver until the calling coroutine is cancelled; everything is closed on the way out.
     * Only one [run] at a time.
     */
    public suspend fun run()
}

/**
 * One finger on the CarPlay picture (DiPlay AirPlayContact): [x] and [y] from 0 (left, top) to 1 (right, bottom) of
 * the picture as shown, which the engine scales to the canvas the iPhone was told ([CarPlayConfig.widthPixels],
 * [CarPlayConfig.heightPixels]). Values outside 0..1 are clamped.
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
 * [widthMillimetres], [maxFps]) goes into `/info` (displays and the touchscreen's HID descriptor) and sizes the
 * decoder at the stream SETUP. DiPlay's video decoders need even sides (CarPlayHostActivity.evenDisplaySize).
 */
public data class CarPlayConfig(
    /** Name shown on the iPhone and in its Bonjour browser. */
    val name: String = "Handlebar Link",
    /** Model identifier. */
    val model: String = "HandlebarLink1,1",
    /** Manufacturer. */
    val manufacturer: String = "Handlebar Link",
    /** The CarPlay canvas width in pixels. */
    val widthPixels: Int = 1280,
    /** The CarPlay canvas height in pixels. */
    val heightPixels: Int = 720,
    /** Physical width of the canvas in millimetres; smaller values give larger controls. */
    val widthMillimetres: Int = 150,
    /** Highest frame rate to ask for, 30..60. */
    val maxFps: Int = 60,
) {
    init {
        require(widthPixels > 0 && heightPixels > 0) { "Canvas size must be positive" }
        require(widthMillimetres in 1..MAX_MILLIMETRES) { "Physical width must be 1..$MAX_MILLIMETRES mm" }
        require(maxFps in MIN_FPS..MAX_FPS) { "Frame rate must be $MIN_FPS..$MAX_FPS" }
        require(widthPixels <= MAX_SIDE && heightPixels <= MAX_SIDE) { "Canvas sides must be at most $MAX_SIDE" }
    }

    /** The frame rate `/info` reports and the decoder is sized for: [maxFps] down to a multiple of 5 (DiPlay). */
    internal val reportedFps: Int get() = maxFps / FPS_STEP * FPS_STEP

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

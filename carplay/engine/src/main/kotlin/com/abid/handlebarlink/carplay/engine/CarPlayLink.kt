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
     * Runs the receiver until the calling coroutine is cancelled; everything is closed on the way out.
     * Only one [run] at a time.
     */
    public suspend fun run()
}

/** What the receiver tells the iPhone about itself and the screen. */
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
    }

    private companion object {
        /** DiPlay's reported-size clamp (AirPlayDisplaySettings). */
        const val MAX_MILLIMETRES = 2000
        const val MIN_FPS = 30
        const val MAX_FPS = 60
    }
}

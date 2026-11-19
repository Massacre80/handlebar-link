package com.abid.handlebarlink.carplay.engine.android

import android.view.SurfaceView
import com.abid.handlebarlink.carplay.engine.android.video.AndroidScreenPort
import com.abid.handlebarlink.carplay.engine.android.video.CarPlayVideoStats
import com.abid.handlebarlink.carplay.engine.android.video.ScreenSurface
import com.abid.handlebarlink.carplay.engine.android.video.createScreenDecoder
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.flow.StateFlow

/**
 * The CarPlay picture on this phone: the hardware screen decoder that the engine feeds (through
 * [createAndroidCarPlayPorts]) and the app shows in a `SurfaceView` with [show]. One per process (Hilt `@Singleton`):
 * the decoder outlives sessions and the screen being shown, so leaving the CarPlay display only detaches its surface
 * (decoders with `FEATURE_DetachedSurface` keep decoding) and coming back reattaches it and asks the iPhone for a key
 * frame (ScreenDecoder).
 */
public class CarPlayScreen internal constructor(
    internal val port: AndroidScreenPort,
) {
    /**
     * Draws the picture into [view] whenever its surface exists, until the returned handle is closed. Call from the
     * main thread, once per view.
     */
    public fun show(view: SurfaceView): AutoCloseable = ScreenSurface.bind(view, port.decoder)

    /** The picture's last window of numbers in the current screen stream (null until one ended), as it changes. */
    public val stats: StateFlow<CarPlayVideoStats?> get() = port.stats
}

/**
 * Creates the CarPlay picture's decoder. Only constructs objects (main-thread safe, docs/LESSONS.md 60); the decoder's
 * thread starts with the first stream. Logs `carplay.video.*` through [logger].
 */
public fun createCarPlayScreen(logger: Logger): CarPlayScreen =
    CarPlayScreen(AndroidScreenPort { listener -> createScreenDecoder(logger, listener) })

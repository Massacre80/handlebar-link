package com.abid.handlebarlink.carplay.engine.android.video

import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Connects a `SurfaceView` to a [ScreenDecoder]: the decoder draws to the view's surface while it exists and lets go
 * of it in `surfaceDestroyed`. A `SurfaceView` is the right view for this (Media3 "Surface types": lower power, more
 * accurate frame timing, "should be preferred over TextureView where possible"; the hardware composer shows it as an
 * overlay without a GPU copy, docs/PLAN.md "Performance rules").
 *
 * From Android 14 the surface follows the view's attachment instead of its visibility
 * (`SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT`, API 34, the one branch for it), so hiding the view or turning the screen
 * off keeps the surface and the decoder; Media3's `PlayerView` does the same from Android 14 (Media3 "Surface
 * types"). Call from the main thread; [close] when the view is done with.
 */
public class ScreenSurface private constructor(
    private val view: SurfaceView,
    private val decoder: ScreenDecoder,
) : AutoCloseable {
    private val callback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                decoder.attach(holder.surface)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                decoder.attach(holder.surface) // the same surface is ignored
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                decoder.detach()
            }
        }

    private fun bind() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        }
        view.holder.addCallback(callback)
        view.holder.surface
            ?.takeIf { it.isValid }
            ?.let(decoder::attach)
    }

    /** Stops drawing to the view and forgets it. */
    override fun close() {
        view.holder.removeCallback(callback)
        decoder.detach()
    }

    /** Binding. */
    public companion object {
        /** Makes [decoder] draw to [view]'s surface whenever it exists. */
        public fun bind(
            view: SurfaceView,
            decoder: ScreenDecoder,
        ): ScreenSurface = ScreenSurface(view, decoder).also { it.bind() }
    }
}

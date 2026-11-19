package com.abid.handlebarlink.carplay.engine.android

import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import com.abid.handlebarlink.carplay.engine.android.video.AVC_MIME
import com.abid.handlebarlink.carplay.engine.android.video.MediaCodecCatalog
import com.abid.handlebarlink.carplay.engine.android.video.chooseDecoders
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the whole CarPlay canvas lies in the view that shows it, in that view's pixels: [left], [top], [width],
 * [height] (DiPlay CarPlayVideoLayout). Touches follow the same rectangle (`CarPlayTouch.contacts`).
 */
public data class CarPlayPictureLayout(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    /**
     * The integer rectangle a `SurfaceView` showing the canvas takes (DiPlay CarPlaySurfaceBounds.from,
     * CarPlayVideoSurface.kt:44-54): each edge rounded, at least 1 pixel each way. It may reach past the view: DiPlay
     * anchors every view area at the canvas's top-left corner, so the rest of the canvas lies to the right of or below
     * the window, which crops it.
     */
    public val bounds: CarPlaySurfaceBounds
        get() {
            val boundsLeft = left.roundToInt()
            val boundsTop = top.roundToInt()
            return CarPlaySurfaceBounds(
                boundsLeft,
                boundsTop,
                ((left + width).roundToInt() - boundsLeft).coerceAtLeast(1),
                ((top + height).roundToInt() - boundsTop).coerceAtLeast(1),
            )
        }

    /** This layout in the coordinates of the `SurfaceView` placed at [bounds], which receives the touches. */
    public fun inSurface(): CarPlayPictureLayout = bounds.let { copy(left = left - it.left, top = top - it.top) }

    /** Builds layouts. */
    public companion object {
        /**
         * The canvas of [canvasWidth] x [canvasHeight] pixels in a view of [viewWidth] x [viewHeight] while CarPlay
         * draws in [area], as DiPlay's `contentRect` lays it out (CarPlayHostActivity.kt:6152-6166): an area smaller
         * than the canvas fills the view, scaled on each axis on its own, and the rest of the canvas falls outside the
         * view; an area of the whole canvas keeps its aspect ratio, centred (CarPlayVideoLayout.fit,
         * CarPlayVideoLayout.kt:9-17).
         */
        public fun of(
            canvasWidth: Int,
            canvasHeight: Int,
            area: CarPlayViewArea,
            viewWidth: Int,
            viewHeight: Int,
        ): CarPlayPictureLayout {
            if (area.width != canvasWidth || area.height != canvasHeight) {
                val scaleX = viewWidth.toFloat() / area.width
                val scaleY = viewHeight.toFloat() / area.height
                return CarPlayPictureLayout(
                    -area.originX * scaleX,
                    -area.originY * scaleY,
                    canvasWidth * scaleX,
                    canvasHeight * scaleY,
                )
            }
            return fit(canvasWidth, canvasHeight, viewWidth, viewHeight)
        }

        private fun fit(
            canvasWidth: Int,
            canvasHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
        ): CarPlayPictureLayout {
            val empty = min(canvasWidth, canvasHeight) <= 0 || min(viewWidth, viewHeight) <= 0
            if (empty) {
                return CarPlayPictureLayout(
                    0f,
                    0f,
                    viewWidth.coerceAtLeast(0).toFloat(),
                    viewHeight.coerceAtLeast(0).toFloat(),
                )
            }
            val scale = min(viewWidth.toFloat() / canvasWidth, viewHeight.toFloat() / canvasHeight)
            val width = canvasWidth * scale
            val height = canvasHeight * scale
            return CarPlayPictureLayout((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
        }
    }
}

/** A `SurfaceView`'s place in its parent, in whole pixels ([CarPlayPictureLayout.bounds]). */
public data class CarPlaySurfaceBounds(
    /** Its left edge in the parent. */
    val left: Int,
    /** Its top edge in the parent. */
    val top: Int,
    /** Its width, at least 1. */
    val width: Int,
    /** Its height, at least 1. */
    val height: Int,
)

/**
 * Whether the screen decoder has a decoder for an H.264 picture of [width] x [height]: a hardware decoder that is not
 * an alias or secure-only and lists the size (`VideoCapabilities.isSizeSupported`), the decoders the screen decoder
 * chooses from (`chooseDecoders`). Asks `MediaCodecList` (a Binder call): call it off the main thread, and once.
 *
 * DiPlay asks the same of the first listed decoder before it declares a square canvas (CarPlayRotation.squareSide,
 * CarPlayRotation.kt:39-78); its extra probe that configures a decoder for a square the decoder does not list is
 * left out, because our screen decoder only takes decoders that list the size.
 */
public fun screenDecoderTakes(
    width: Int,
    height: Int,
): Boolean = chooseDecoders(MediaCodecCatalog.decoders(AVC_MIME, width, height)).isNotEmpty()

package com.abid.handlebarlink.carplay.engine.android.touch

import android.view.MotionEvent
import com.abid.handlebarlink.carplay.engine.TouchContact
import com.abid.handlebarlink.carplay.engine.android.CarPlayPictureLayout

/**
 * Turns a touch on the view that shows the CarPlay picture into what `CarPlayLink.touch` takes, as DiPlay's
 * CarPlayTouchMapper does (CarPlayTouchMapper.kt:11-33): one contact per pointer, its id the pointer id (which stays
 * with its finger; the engine picks the report slots), positions as fractions of the view clamped to 0..1, every
 * pointer up on `ACTION_UP` and `ACTION_CANCEL`, and only the lifting pointer up on `ACTION_POINTER_UP`. Positions are
 * fractions of the whole canvas: of the view when it shows exactly the canvas, else of the canvas's rectangle in the
 * view ([CarPlayPictureLayout], DiPlay's second overload), so a touch in a view area lands at its place in the canvas.
 */
public object CarPlayTouch {
    /** The contacts of [event] on a view of [viewWidth] x [viewHeight] pixels that shows exactly the canvas. */
    public fun contacts(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
    ): List<TouchContact> = contacts(pointers(event), viewWidth, viewHeight)

    /** The contacts of [event] on a view where the canvas lies at [content] (in that view's pixels). */
    public fun contacts(
        event: MotionEvent,
        content: CarPlayPictureLayout,
    ): List<TouchContact> = contacts(pointers(event), content)

    private fun pointers(event: MotionEvent) =
        Pointers(
            action = event.actionMasked,
            actionIndex = event.actionIndex,
            ids = IntArray(event.pointerCount) { event.getPointerId(it) },
            xs = FloatArray(event.pointerCount) { event.getX(it) },
            ys = FloatArray(event.pointerCount) { event.getY(it) },
        )

    /** One touch event's pointers, by pointer index (MotionEvent's view of them). */
    internal class Pointers(
        val action: Int,
        val actionIndex: Int,
        val ids: IntArray,
        val xs: FloatArray,
        val ys: FloatArray,
    )

    internal fun contacts(
        pointers: Pointers,
        viewWidth: Int,
        viewHeight: Int,
    ): List<TouchContact> = contacts(pointers, CarPlayPictureLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))

    /** DiPlay CarPlayTouchMapper.contacts(event, content) (CarPlayTouchMapper.kt:14-33). */
    internal fun contacts(
        pointers: Pointers,
        content: CarPlayPictureLayout,
    ): List<TouchContact> {
        val width = content.width.coerceAtLeast(1f).toDouble()
        val height = content.height.coerceAtLeast(1f).toDouble()
        val lifted = if (pointers.action == MotionEvent.ACTION_POINTER_UP) pointers.actionIndex else NONE
        val allUp = pointers.action == MotionEvent.ACTION_UP || pointers.action == MotionEvent.ACTION_CANCEL
        return pointers.ids.indices.map { index ->
            TouchContact(
                pointerId = pointers.ids[index],
                x = ((pointers.xs[index] - content.left).toDouble() / width).coerceIn(0.0, 1.0),
                y = ((pointers.ys[index] - content.top).toDouble() / height).coerceIn(0.0, 1.0),
                down = !allUp && index != lifted,
            )
        }
    }

    private const val NONE = -1
}

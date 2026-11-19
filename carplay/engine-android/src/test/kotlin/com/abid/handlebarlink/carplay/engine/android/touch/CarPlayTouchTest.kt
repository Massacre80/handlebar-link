package com.abid.handlebarlink.carplay.engine.android.touch

import android.view.MotionEvent
import assertk.assertThat
import assertk.assertions.containsExactly
import com.abid.handlebarlink.carplay.engine.TouchContact
import com.abid.handlebarlink.carplay.engine.android.touch.CarPlayTouch.Pointers
import org.junit.Test

/**
 * The view-to-contact mapping against DiPlay's CarPlayTouchMapper (CarPlayTouchMapper.kt:14-33), whose rules give the
 * expected values: x / width and y / height clamped to 0..1, up for every pointer on ACTION_UP and ACTION_CANCEL, up
 * only for the action's pointer on ACTION_POINTER_UP. The view is 1080 x 2250, so the expected fractions are exact.
 */
class CarPlayTouchTest {
    private fun contacts(
        action: Int,
        actionIndex: Int = 0,
        vararg pointers: Triple<Int, Float, Float>,
    ) = CarPlayTouch.contacts(
        Pointers(
            action,
            actionIndex,
            pointers.map { it.first }.toIntArray(),
            pointers.map { it.second }.toFloatArray(),
            pointers.map { it.third }.toFloatArray(),
        ),
        WIDTH,
        HEIGHT,
    )

    @Test
    fun `a moving finger is down at its fraction of the view, with its pointer id`() {
        assertThat(contacts(MotionEvent.ACTION_MOVE, 0, Triple(7, 540f, 562.5f)))
            .containsExactly(TouchContact(7, 0.5, 0.25, down = true))
    }

    @Test
    fun `positions outside the view are clamped to its edges`() {
        assertThat(contacts(MotionEvent.ACTION_DOWN, 0, Triple(0, -10f, 2300f)))
            .containsExactly(TouchContact(0, 0.0, 1.0, down = true))
    }

    @Test
    fun `a second finger lifting is up, the other stays down`() {
        assertThat(contacts(MotionEvent.ACTION_POINTER_UP, 1, Triple(3, 270f, 450f), Triple(5, 810f, 1800f)))
            .containsExactly(TouchContact(3, 0.25, 0.2, down = true), TouchContact(5, 0.75, 0.8, down = false))
    }

    @Test
    fun `the last finger lifting and a cancelled gesture lift every pointer`() {
        assertThat(contacts(MotionEvent.ACTION_UP, 0, Triple(5, 810f, 1800f)))
            .containsExactly(TouchContact(5, 0.75, 0.8, down = false))
        assertThat(contacts(MotionEvent.ACTION_CANCEL, 0, Triple(3, 270f, 450f), Triple(5, 810f, 1800f)))
            .containsExactly(TouchContact(3, 0.25, 0.2, down = false), TouchContact(5, 0.75, 0.8, down = false))
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2250
    }
}

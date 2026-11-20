package com.abid.handlebarlink.ui.frame

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

/**
 * The app header has no background at rest and lifts into its glass only while its page's own scroll is away from the
 * top (owner, 2026-11-20: "on scroll is fine, not initially"; docs/PLAN.md "App header").
 */
class HeaderLiftTest {
    private val lift = HeaderLift()

    /** A scroller's position, as `ScrollState.value` reports it (whole pixels from its top). */
    private class Scroller(
        var value: Int = 0,
    )

    private fun register(
        page: String,
        scroller: Scroller,
    ) = lift.register(page) { scroller.value }

    @Test
    fun `a page at its top leaves the header at rest`() {
        register(HOME, Scroller())

        assertThat(lift.isLifted(HOME)).isFalse()
    }

    @Test
    fun `a page without scrollers leaves the header at rest`() {
        assertThat(lift.isLifted(HOME)).isFalse()
    }

    @Test
    fun `content scrolled under the header lifts it`() {
        val home = Scroller()
        register(HOME, home)

        home.value = 1

        assertThat(lift.isLifted(HOME)).isTrue()
    }

    @Test
    fun `back at the top the header rests again`() {
        val home = Scroller(value = 640)
        register(HOME, home)
        assertThat(lift.isLifted(HOME)).isTrue()

        home.value = 0

        assertThat(lift.isLifted(HOME)).isFalse()
    }

    @Test
    fun `a tab at its top shows no glass whatever the last tab did`() {
        register(GARAGE, Scroller(value = 900))
        register(HOME, Scroller())

        assertThat(lift.isLifted(GARAGE)).isTrue()
        assertThat(lift.isLifted(HOME)).isFalse()
    }

    @Test
    fun `on its side either pane scrolled lifts it`() {
        val bike = Scroller()
        val cards = Scroller()
        register(HOME, bike)
        register(HOME, cards)

        cards.value = 120
        assertThat(lift.isLifted(HOME)).isTrue()

        cards.value = 0
        assertThat(lift.isLifted(HOME)).isFalse()
    }

    @Test
    fun `a scroller that left the screen no longer lifts it`() {
        val token = register(HOME, Scroller(value = 300))

        lift.unregister(token)

        assertThat(lift.isLifted(HOME)).isFalse()
    }

    private companion object {
        const val HOME = "HomeRoute"
        const val GARAGE = "GarageRoute"
    }
}

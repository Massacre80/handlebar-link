package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import org.junit.Test

class ControlRouterTest {
    @Test
    fun `every handlebar press has a defined action or is deliberately free`() {
        val expected =
            mapOf(
                HandlebarSwitch.UP to ControlAction.PREVIOUS,
                HandlebarSwitch.DOWN to ControlAction.NEXT,
                HandlebarSwitch.RIGHT to ControlAction.PLAY_PAUSE,
                HandlebarSwitch.HOLD_DOWN to ControlAction.PLAY_PAUSE,
                // Free until our own CarPlay receiver can open Siri.
                HandlebarSwitch.LEFT to null,
                HandlebarSwitch.HOLD_LEFT to null,
            )

        assertThat(expected.keys).isEqualTo(HandlebarSwitch.entries.toSet())
        expected.forEach { (switch, action) -> assertThat(ControlRouter.actionFor(switch)).isEqualTo(action) }
    }
}

package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import org.junit.Test

class HandlebarMappingTest {
    @Test
    fun `every press the phone receives has a default`() {
        assertThat(HANDLEBAR_DEFAULTS.keys).isEqualTo(HandlebarSwitch.entries.toSet())
    }

    @Test
    fun `nothing set keeps today's handlebar`() {
        val mapping = mappingFrom(emptyMap())
        assertThat(mapping[HandlebarSwitch.UP]).isEqualTo(RiderAction.PREVIOUS)
        assertThat(mapping[HandlebarSwitch.DOWN]).isEqualTo(RiderAction.NEXT)
        assertThat(mapping[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.PLAY_PAUSE)
        assertThat(mapping[HandlebarSwitch.HOLD_DOWN]).isEqualTo(RiderAction.PLAY_PAUSE)
        assertThat(mapping[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.NOTHING)
        assertThat(mapping[HandlebarSwitch.HOLD_LEFT]).isEqualTo(RiderAction.NOTHING)
    }

    @Test
    fun `a press set by the rider does what was set, the others keep theirs`() {
        val mapping = mappingFrom(mapOf(HandlebarSwitch.LEFT to "BATTERY", HandlebarSwitch.UP to "NEXT"))
        assertThat(mapping[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.BATTERY)
        assertThat(mapping[HandlebarSwitch.UP]).isEqualTo(RiderAction.NEXT)
        assertThat(mapping[HandlebarSwitch.DOWN]).isEqualTo(RiderAction.NEXT)
    }

    @Test
    fun `an unknown stored name falls back to the default, never to nothing`() {
        assertThat(mappingFrom(mapOf(HandlebarSwitch.RIGHT to "SIRI"))[HandlebarSwitch.RIGHT])
            .isEqualTo(RiderAction.PLAY_PAUSE)
    }
}

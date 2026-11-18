package com.abid.handlebarlink.controls

import assertk.all
import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.containsNone
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import org.junit.Test

class HandlebarMappingTest {
    private val riding = CallSituation.RIDING
    private val ringing = CallSituation.RINGING
    private val onCall = CallSituation.ON_CALL

    @Test
    fun `every situation has a default for every press the phone receives`() {
        assertThat(HANDLEBAR_DEFAULTS.keys).isEqualTo(CallSituation.entries.toSet())
        HANDLEBAR_DEFAULTS.values.forEach { assertThat(it.keys).isEqualTo(HandlebarSwitch.entries.toSet()) }
    }

    @Test
    fun `riding keeps today's handlebar, with Siri on hold left`() {
        val mapping = mappingFrom(emptyMap()).getValue(riding)
        assertThat(mapping[HandlebarSwitch.UP]).isEqualTo(RiderAction.PREVIOUS)
        assertThat(mapping[HandlebarSwitch.DOWN]).isEqualTo(RiderAction.NEXT)
        assertThat(mapping[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.PLAY_PAUSE)
        assertThat(mapping[HandlebarSwitch.HOLD_DOWN]).isEqualTo(RiderAction.PLAY_PAUSE)
        assertThat(mapping[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.NOTHING)
        assertThat(mapping[HandlebarSwitch.HOLD_LEFT]).isEqualTo(RiderAction.SIRI)
    }

    @Test
    fun `while ringing, the phone key answers and holding it declines`() {
        val mapping = mappingFrom(emptyMap()).getValue(ringing)
        assertThat(mapping[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.ANSWER_CALL)
        assertThat(mapping[HandlebarSwitch.HOLD_LEFT]).isEqualTo(RiderAction.DECLINE_CALL)
        assertThat(mapping[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.NOTHING)
        assertThat(mapping[HandlebarSwitch.UP]).isEqualTo(RiderAction.NOTHING)
        assertThat(mapping[HandlebarSwitch.DOWN]).isEqualTo(RiderAction.NOTHING)
        assertThat(mapping[HandlebarSwitch.HOLD_DOWN]).isEqualTo(RiderAction.NOTHING)
    }

    @Test
    fun `on a call, the phone key mutes the mic, holding it ends, up and down set the volume`() {
        val mapping = mappingFrom(emptyMap()).getValue(onCall)
        assertThat(mapping[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.MUTE_MIC)
        assertThat(mapping[HandlebarSwitch.HOLD_LEFT]).isEqualTo(RiderAction.END_CALL)
        assertThat(mapping[HandlebarSwitch.UP]).isEqualTo(RiderAction.VOLUME_UP)
        assertThat(mapping[HandlebarSwitch.DOWN]).isEqualTo(RiderAction.VOLUME_DOWN)
        assertThat(mapping[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.NOTHING)
        assertThat(mapping[HandlebarSwitch.HOLD_DOWN]).isEqualTo(RiderAction.NOTHING)
    }

    @Test
    fun `every default is an action its situation offers`() {
        HANDLEBAR_DEFAULTS.forEach { (situation, layer) ->
            layer.values.forEach { assertThat(situation.allows(it), "$situation $it").isTrue() }
        }
    }

    @Test
    fun `call actions are offered only in their call situation, music keys only while riding`() {
        val music = listOf(RiderAction.PLAY_PAUSE, RiderAction.NEXT, RiderAction.PREVIOUS)
        assertThat(allowedActions(riding)).all {
            containsAtLeast(*music.toTypedArray(), RiderAction.SIRI, RiderAction.NOTHING)
            containsNone(RiderAction.ANSWER_CALL, RiderAction.DECLINE_CALL, RiderAction.END_CALL, RiderAction.MUTE_MIC)
        }
        assertThat(allowedActions(ringing)).all {
            containsAtLeast(RiderAction.ANSWER_CALL, RiderAction.DECLINE_CALL, RiderAction.NOTHING)
            containsNone(*music.toTypedArray(), RiderAction.END_CALL, RiderAction.MUTE_MIC, RiderAction.SIRI)
        }
        assertThat(allowedActions(onCall)).all {
            containsAtLeast(RiderAction.END_CALL, RiderAction.MUTE_MIC, RiderAction.VOLUME_UP, RiderAction.NOTHING)
            containsNone(*music.toTypedArray(), RiderAction.ANSWER_CALL, RiderAction.DECLINE_CALL, RiderAction.SIRI)
        }
    }

    @Test
    fun `a press set by the rider does what was set, the others keep theirs`() {
        val mapping =
            mappingFrom(
                mapOf(
                    riding to mapOf(HandlebarSwitch.LEFT to "BATTERY", HandlebarSwitch.UP to "NEXT"),
                    onCall to mapOf(HandlebarSwitch.RIGHT to "END_CALL"),
                ),
            )
        assertThat(mapping.getValue(riding)[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.BATTERY)
        assertThat(mapping.getValue(riding)[HandlebarSwitch.UP]).isEqualTo(RiderAction.NEXT)
        assertThat(mapping.getValue(riding)[HandlebarSwitch.DOWN]).isEqualTo(RiderAction.NEXT)
        // End call is allowed on any press, not only on the hold it defaults to.
        assertThat(mapping.getValue(onCall)[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.END_CALL)
        assertThat(mapping.getValue(ringing)).isEqualTo(HANDLEBAR_DEFAULTS.getValue(ringing))
    }

    @Test
    fun `an unknown stored name falls back to the default, never to nothing`() {
        val mapping = mappingFrom(mapOf(riding to mapOf(HandlebarSwitch.RIGHT to "WARP_DRIVE")))
        assertThat(mapping.getValue(riding)[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.PLAY_PAUSE)
    }

    @Test
    fun `a stored action its situation does not offer falls back to the default`() {
        val mapping = mappingFrom(mapOf(onCall to mapOf(HandlebarSwitch.LEFT to "NEXT")))
        assertThat(mapping.getValue(onCall)[HandlebarSwitch.LEFT]).isEqualTo(RiderAction.MUTE_MIC)
    }

    @Test
    fun `riding keeps the keys from before situations, so riders keep their settings`() {
        // The keys the app stored before situations existed (DataStoreHandlebarMapping on v2).
        assertThat(mappingKey(riding, HandlebarSwitch.UP)).isEqualTo("handlebar_UP")
        assertThat(mappingKey(riding, HandlebarSwitch.HOLD_LEFT)).isEqualTo("handlebar_HOLD_LEFT")
    }

    @Test
    fun `every situation and press has its own key`() {
        val keys = CallSituation.entries.flatMap { s -> HandlebarSwitch.entries.map { mappingKey(s, it) } }
        assertThat(keys.toSet().size).isEqualTo(CallSituation.entries.size * HandlebarSwitch.entries.size)
    }
}

package com.abid.handlebarlink.carplay.protocol.iap2

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.prop
import com.abid.handlebarlink.carplay.protocol.hex
import org.junit.Test

/**
 * CallStateUpdate parsing against messages built with Python's struct (`src/test/resources/iap2/call_state_update.py`,
 * run with `python3 -I call_state_update.py`), never with our encoder. The cases follow DiPlay's CarPlayCallStateTest
 * and `BydCarPlayCall.kt:34-51`.
 */
class CallStateUpdateTest {
    private fun parse(bytes: String): CallStateUpdate? =
        CallStateUpdate.parse(Iap2ControlStream().receive(hex(bytes)).single())

    @Test
    fun `a ringing call from DiPlay's test is known by its UUID, extra parameters skipped`() {
        val update = parse(RINGING_A)

        assertThat(update).isNotNull().prop(CallStateUpdate::status).isEqualTo(CallStateUpdate.RINGING)
        assertThat(update!!.callKey).isEqualTo("a")
    }

    @Test
    fun `an outgoing call with a remote id and a UUID is known by the UUID`() {
        val update = parse(SENDING_B)!!

        assertThat(update.status).isEqualTo(CallStateUpdate.SENDING)
        assertThat(update.callKey).isEqualTo("b")
    }

    @Test
    fun `without a UUID the call is known by its trimmed remote id`() {
        val update = parse(ACTIVE_REMOTE_ONLY)!!

        assertThat(update.status).isEqualTo(CallStateUpdate.ACTIVE)
        assertThat(update.callKey).isEqualTo("+441234")
    }

    @Test
    fun `a blank UUID falls back to the remote id`() {
        val update = parse(HELD_BLANK_UUID)!!

        assertThat(update.status).isEqualTo(CallStateUpdate.HELD)
        assertThat(update.callKey).isEqualTo("+441234")
    }

    @Test
    fun `an update without a one-byte status is ignored, as DiPlay does`() {
        assertThat(parse(NO_STATUS)).isNull()
        assertThat(parse(WIDE_STATUS)).isNull()
    }

    @Test
    fun `a malformed remote id or UUID counts as absent`() {
        assertThat(parse(BAD_REMOTE)!!.callKey).isEqualTo("c")
        assertThat(parse(BAD_UUID)!!.callKey).isEqualTo("+44")
    }

    @Test
    fun `other messages are not call updates, even with a parameter 2`() {
        assertThat(parse(NOW_PLAYING)).isNull()
    }

    @Test
    fun `the ending statuses and unknown ones come through as sent`() {
        assertThat(parse(DISCONNECTED_A)!!.status).isEqualTo(CallStateUpdate.DISCONNECTED)
        assertThat(parse(DISCONNECTING_B)!!.status).isEqualTo(CallStateUpdate.DISCONNECTING)
        assertThat(parse(UNKNOWN_A)!!.status).isEqualTo(9)
    }

    @Test
    fun `the text form never shows the call's number`() {
        assertThat(parse(ACTIVE_REMOTE_ONLY).toString()).doesNotContain("441234")
    }

    @Test
    fun `the statuses are DiPlay's`() {
        // BydCarPlayCall.kt:82-88.
        assertThat(
            listOf(
                CallStateUpdate.DISCONNECTED,
                CallStateUpdate.SENDING,
                CallStateUpdate.RINGING,
                CallStateUpdate.CONNECTING,
                CallStateUpdate.ACTIVE,
                CallStateUpdate.HELD,
                CallStateUpdate.DISCONNECTING,
            ),
        ).isEqualTo((0..6).toList())
        assertThat(CallStateUpdate.MESSAGE_ID).isEqualTo(0x4155)
    }

    private companion object {
        // call_state_update.py output.
        const val RINGING_A = "4040001e4155000800014d756d0000050002020005000301000600046100"
        const val SENDING_B = "404000284155001200002b3937313530303030303030300000050002010005000301000600046200"
        const val ACTIVE_REMOTE_ONLY = "404000194155000e0000202b34343132333420000005000204"
        const val HELD_BLANK_UUID = "4040001e4155000c00002b34343132333400000500020500070004202000"
        const val NO_STATUS = "4040000c4155000600046100"
        const val WIDE_STATUS = "404000124155000600020002000600046100"
        const val BAD_REMOTE = "404000184155000700002b34340005000202000600046300"
        const val BAD_UUID = "404000184155000800002b34340000050002020005000463"
        const val NOW_PLAYING = "4040000b50010005000202"
        const val DISCONNECTED_A = "4040001141550005000200000600046100"
        const val DISCONNECTING_B = "4040001141550005000206000600046200"
        const val UNKNOWN_A = "4040001141550005000209000600046100"
    }
}

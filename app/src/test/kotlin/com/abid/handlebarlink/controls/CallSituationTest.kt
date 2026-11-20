package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayCallStatus
import org.junit.Test

class CallSituationTest {
    @Test
    fun `the situation is the iPhone's calls while our CarPlay is connected, else riding`() {
        val ringing = listOf(CarPlayCall(CarPlayCallStatus.RINGING))
        val active = listOf(CarPlayCall(CarPlayCallStatus.ACTIVE))

        assertThat(carPlaySituation(connected = true, ringing)).isEqualTo(CallSituation.RINGING)
        assertThat(carPlaySituation(connected = true, active)).isEqualTo(CallSituation.ON_CALL)
        assertThat(carPlaySituation(connected = true, emptyList())).isEqualTo(CallSituation.RIDING)
        // Not connected: no call reaches the handlebar (calls the engine kept are not trusted).
        assertThat(carPlaySituation(connected = false, ringing)).isEqualTo(CallSituation.RIDING)
        assertThat(carPlaySituation(connected = false, active)).isEqualTo(CallSituation.RIDING)
    }

    @Test
    fun `iAP2 call statuses give the situation, ringing first (DiPlay BydCarPlayCall)`() {
        // CallStateUpdate 0x4155 status: 0 disconnected, 1 sending, 2 ringing, 3 connecting, 4 active,
        // 5 held, 6 disconnecting.
        assertThat(situationOfCalls(emptyList())).isEqualTo(CallSituation.RIDING)
        assertThat(situationOfCalls(listOf(0))).isEqualTo(CallSituation.RIDING)
        assertThat(situationOfCalls(listOf(2))).isEqualTo(CallSituation.RINGING)
        listOf(1, 3, 4, 5, 6).forEach {
            assertThat(situationOfCalls(listOf(it)), "status $it").isEqualTo(CallSituation.ON_CALL)
        }
        // Call waiting: a second call rings during an active one; the keys answer or decline it.
        assertThat(situationOfCalls(listOf(4, 2))).isEqualTo(CallSituation.RINGING)
        // An unknown status is not a call.
        assertThat(situationOfCalls(listOf(99))).isEqualTo(CallSituation.RIDING)
    }
}

// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CallSituationTest {
    @Test
    fun `the audio source follows the call, on a call while it runs and riding after`() =
        runTest {
            val inCall = MutableSharedFlow<Boolean>()
            val seen = mutableListOf<CallSituation>()
            val situation = situationsFrom(inCall, backgroundScope) { seen += it }
            runCurrent()
            assertThat(situation.value).isEqualTo(CallSituation.RIDING)

            inCall.emit(true)
            runCurrent()
            assertThat(situation.value).isEqualTo(CallSituation.ON_CALL)

            inCall.emit(false)
            runCurrent()
            assertThat(situation.value).isEqualTo(CallSituation.RIDING)
            assertThat(seen).containsExactly(CallSituation.ON_CALL, CallSituation.RIDING)
        }

    @Test
    fun `a source started during a call is on a call from the first read`() =
        runTest {
            // A process restarted mid-call: the seed comes from one read of the audio state.
            val situation = situationsFrom(MutableSharedFlow(), backgroundScope, CallSituation.ON_CALL)

            assertThat(situation.value).isEqualTo(CallSituation.ON_CALL)
        }

    @Test
    fun `the audio source never reports ringing, whatever the audio does`() =
        runTest {
            val inCall = MutableSharedFlow<Boolean>()
            val seen = mutableListOf<CallSituation>()
            val situation = situationsFrom(inCall, backgroundScope) { seen += it }
            runCurrent()

            repeat(20) { i ->
                inCall.emit(i % 3 != 0)
                runCurrent()
                seen += situation.value
            }

            assertThat(seen).doesNotContain(CallSituation.RINGING)
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

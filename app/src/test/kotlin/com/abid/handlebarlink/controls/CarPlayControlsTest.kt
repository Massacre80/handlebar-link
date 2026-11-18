// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CarPlayControlsTest {
    private val record = FakeMicMuteRecord()
    private val flagWhenAsked = mutableListOf<Pair<Boolean, Boolean>>()
    private val microphone = FakeMicrophone(onSetMuted = { flagWhenAsked += it to record.flag })
    private val situations = FakeCallSituations(CallSituation.ON_CALL)
    private val log = RecordingLogSink()

    /** The controls of a process that has just started (HandlebarLinkApp calls start). */
    private fun TestScope.calls(
        target: ControlTarget = FakeControlTarget(),
        scope: CoroutineScope = backgroundScope,
    ) = CarPlayControls(
        target,
        microphone,
        situations,
        record,
        scope,
        testLogger(log, TestTimeProvider(testScheduler)),
    ).also {
        it.start()
        runCurrent()
    }

    /** A process that muted the mic on a call and then died; the mute and the flag stay, as on the phone. */
    private suspend fun TestScope.diedWhileMuted() {
        val process = CoroutineScope(backgroundScope.coroutineContext + Job())
        calls(scope = process).perform(RiderAction.MUTE_MIC)
        process.cancel()
        runCurrent()
        assertThat(microphone.isMuted).isTrue()
        assertThat(record.flag).isTrue()
    }

    @Test
    fun `the flag is stored before the microphone is muted, and cleared once unmuted`() =
        runTest {
            val calls = calls()

            calls.perform(RiderAction.MUTE_MIC)
            calls.perform(RiderAction.MUTE_MIC)

            // (mute asked, flag at that moment): set before the mute; cleared only after the unmute.
            assertThat(flagWhenAsked).containsExactly(true to true, false to true)
            assertThat(record.flag).isFalse()
        }

    @Test
    fun `a restart after a death mid-call with no call now unmutes and clears the flag`() =
        runTest {
            diedWhileMuted()
            situations.situation.value = CallSituation.RIDING

            calls()

            assertThat(microphone.isMuted).isFalse()
            assertThat(record.flag).isFalse()
            assertThat(log.events("control.mic.unmuted_after_restart")).hasSize(1)
        }

    @Test
    fun `a restart during the same call keeps the mute until that call ends`() =
        runTest {
            diedWhileMuted()

            calls()
            assertThat(microphone.isMuted).isTrue()
            assertThat(record.flag).isTrue()

            situations.situation.value = CallSituation.RIDING
            runCurrent()
            assertThat(microphone.isMuted).isFalse()
            assertThat(record.flag).isFalse()
        }

    @Test
    fun `a restart without the flag leaves a microphone someone else muted alone`() =
        runTest {
            microphone.mutedNow = true
            situations.situation.value = CallSituation.RIDING

            calls()

            assertThat(microphone.isMuted).isTrue()
            assertThat(microphone.requests).isEmpty()
            assertThat(log.events("control.mic.unmuted_after_restart")).isEmpty()
        }

    @Test
    fun `an unmute Android ignores keeps the flag for the next start`() =
        runTest {
            diedWhileMuted()
            situations.situation.value = CallSituation.RIDING
            microphone.refuses = true

            calls()

            assertThat(microphone.isMuted).isTrue()
            assertThat(record.flag).isTrue()
        }

    @Test
    fun `a flag that cannot be stored stops the mute, so no mute is ever left unrecorded`() =
        runTest {
            val calls = calls()
            record.failWrites = true

            assertThat(calls.perform(RiderAction.MUTE_MIC)).isEqualTo(ControlResult.NotDone(Unavailable.MIC_REFUSED))
            assertThat(microphone.requests).isEmpty()
            assertThat(log.events("control.mic.record_failed")).hasSize(1)
        }

    @Test
    fun `a failed flag write while unmuting does not end the watch, the next call is unmuted too`() =
        runTest {
            val calls = calls()
            calls.perform(RiderAction.MUTE_MIC)
            record.failWrites = true
            situations.situation.value = CallSituation.RIDING
            runCurrent()
            assertThat(microphone.isMuted).isFalse()

            record.failWrites = false
            situations.situation.value = CallSituation.ON_CALL
            runCurrent()
            calls.perform(RiderAction.MUTE_MIC)
            situations.situation.value = CallSituation.RIDING
            runCurrent()

            assertThat(microphone.isMuted).isFalse()
            assertThat(log.events("control.mic.unmuted_after_call")).hasSize(2)
        }

    @Test
    fun `an unreadable flag at start leaves the microphone alone and still watches the calls`() =
        runTest {
            microphone.mutedNow = true
            record.failReads = true
            situations.situation.value = CallSituation.RIDING
            val calls = calls()
            assertThat(microphone.requests).isEmpty()

            microphone.mutedNow = false
            situations.situation.value = CallSituation.ON_CALL
            runCurrent()
            calls.perform(RiderAction.MUTE_MIC)
            situations.situation.value = CallSituation.RIDING
            runCurrent()

            assertThat(microphone.isMuted).isFalse()
        }

    @Test
    fun `mute mic mutes the phone's microphone on a call, and a second press unmutes it`() =
        runTest {
            val calls = calls()

            assertThat(calls.perform(RiderAction.MUTE_MIC)).isEqualTo(ControlResult.Done)
            assertThat(microphone.isMuted).isTrue()
            assertThat(calls.perform(RiderAction.MUTE_MIC)).isEqualTo(ControlResult.Done)
            assertThat(microphone.isMuted).isFalse()
        }

    @Test
    fun `the microphone is unmuted when the call ends`() =
        runTest {
            val calls = calls()
            calls.perform(RiderAction.MUTE_MIC)

            situations.situation.value = CallSituation.RIDING
            runCurrent()

            assertThat(microphone.isMuted).isFalse()
            assertThat(log.events("control.mic.unmuted_after_call")).hasSize(1)
        }

    @Test
    fun `the microphone is unmuted when the call turns into a ringing one too`() =
        runTest {
            val calls = calls()
            calls.perform(RiderAction.MUTE_MIC)

            situations.situation.value = CallSituation.RINGING
            runCurrent()

            assertThat(microphone.isMuted).isFalse()
        }

    @Test
    fun `a microphone someone else muted is left alone when the call ends`() =
        runTest {
            calls()
            microphone.mutedNow = true

            situations.situation.value = CallSituation.RIDING
            runCurrent()

            assertThat(microphone.isMuted).isTrue()
            assertThat(microphone.requests).isEmpty()
        }

    @Test
    fun `a microphone unmuted again by the rider is not touched after the call`() =
        runTest {
            val calls = calls()
            calls.perform(RiderAction.MUTE_MIC)
            calls.perform(RiderAction.MUTE_MIC)

            situations.situation.value = CallSituation.RIDING
            runCurrent()

            assertThat(microphone.requests).containsExactly(true, false)
        }

    @Test
    fun `mute mic does nothing outside a call, so it can never stay muted`() =
        runTest {
            situations.situation.value = CallSituation.RIDING
            val calls = calls()

            assertThat(calls.perform(RiderAction.MUTE_MIC)).isEqualTo(ControlResult.NotDone(Unavailable.NO_CALL))
            assertThat(microphone.requests).isEmpty()
        }

    @Test
    fun `a mute Android ignores is reported, not claimed`() =
        runTest {
            microphone.refuses = true
            val calls = calls()

            assertThat(calls.perform(RiderAction.MUTE_MIC)).isEqualTo(ControlResult.NotDone(Unavailable.MIC_REFUSED))
        }

    @Test
    fun `call and Siri actions go to CarPlay as their control action`() =
        runTest {
            val target = FakeControlTarget()
            val calls = calls(target)

            CALL_CONTROLS.keys.forEach { assertThat(calls.perform(it)).isEqualTo(ControlResult.Done) }

            assertThat(target.performed).containsExactly(
                ControlAction.ANSWER_CALL,
                ControlAction.DECLINE_CALL,
                ControlAction.END_CALL,
                ControlAction.SIRI,
            )
        }

    @Test
    fun `with CarLink, call and Siri actions need our own CarPlay`() =
        runTest {
            val calls = calls(FakeControlTarget(CARLINK_SUPPORTED))

            CALL_CONTROLS.keys.forEach {
                assertThat(calls.perform(it), "$it").isEqualTo(ControlResult.NotDone(Unavailable.NEEDS_OUR_CARPLAY))
            }
        }

    @Test
    fun `availability with CarLink, music and mute mic work, calls, Siri and the ringing layer need our CarPlay`() {
        val availability =
            ActionAvailability(
                FakeControlTarget(CARLINK_SUPPORTED),
                microphone,
                FakeCallSituations(seesRinging = false),
            )

        listOf(RiderAction.PLAY_PAUSE, RiderAction.NEXT, RiderAction.MUTE_MIC, RiderAction.TIME, RiderAction.NOTHING)
            .forEach { assertThat(availability.whyUnavailable(it), "$it").isNull() }
        CALL_CONTROLS.keys.forEach {
            assertThat(availability.whyUnavailable(it), "$it").isEqualTo(Unavailable.NEEDS_OUR_CARPLAY)
        }
        assertThat(availability.whyUnavailable(CallSituation.RINGING)).isEqualTo(Unavailable.NEEDS_OUR_CARPLAY)
        assertThat(availability.whyUnavailable(CallSituation.ON_CALL)).isNull()
        assertThat(availability.whyUnavailable(CallSituation.RIDING)).isNull()
    }

    @Test
    fun `availability with our own CarPlay, every action and situation works`() {
        val availability = ActionAvailability(FakeControlTarget(), microphone, FakeCallSituations(seesRinging = true))

        RiderAction.entries.forEach { assertThat(availability.whyUnavailable(it), "$it").isNull() }
        CallSituation.entries.forEach { assertThat(availability.whyUnavailable(it), "$it").isNull() }
    }

    @Test
    fun `availability says when the microphone cannot be changed`() {
        microphone.canMuteNow = false
        val availability = ActionAvailability(FakeControlTarget(), microphone, situations)

        assertThat(availability.whyUnavailable(RiderAction.MUTE_MIC)).isEqualTo(Unavailable.MIC_REFUSED)
    }
}

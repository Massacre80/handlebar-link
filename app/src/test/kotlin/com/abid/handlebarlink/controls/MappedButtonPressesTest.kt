// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MappedButtonPressesTest {
    private val situations = FakeCallSituations()
    private val mapping = InMemoryHandlebarMapping()
    private val banner = RecordingPressFeedback()
    private val log = RecordingLogSink()
    private val performed = mutableListOf<RiderAction>()
    private val microphone = FakeMicrophone()

    /** Presses wired as in the app: call actions through real [CarPlayControls], the rest only recorded. */
    private fun TestScope.presses(target: ControlTarget = FakeControlTarget()): MappedButtonPresses {
        val logger = testLogger(log, TestTimeProvider(testScheduler))
        val calls = CarPlayControls(target, microphone, situations, FakeMicMuteRecord(), backgroundScope, logger)
        calls.start()
        val actions =
            ActionPerformer { action ->
                performed += action
                if (action in CALL_CONTROLS || action == RiderAction.MUTE_MIC) {
                    when (val result = calls.perform(action)) {
                        ControlResult.Done -> ActionOutcome.Done()
                        is ControlResult.NotDone -> ActionOutcome.NotDone(result.reason)
                    }
                } else {
                    ActionOutcome.Done()
                }
            }
        return MappedButtonPresses(mapping, actions, situations, banner, backgroundScope, logger)
            .also { runCurrent() }
    }

    @Test
    fun `a press does its action in the riding layer when no call is on`() =
        runTest {
            val presses = presses()

            presses.press(HandlebarSwitch.RIGHT)
            runCurrent()

            assertThat(performed).containsExactly(RiderAction.PLAY_PAUSE)
            assertThat(banner.done).containsExactly(RiderAction.PLAY_PAUSE to null)
        }

    @Test
    fun `the same press follows the situation, ringing then on a call then riding`() =
        runTest {
            val presses = presses()

            situations.situation.value = CallSituation.RINGING
            presses.press(HandlebarSwitch.HOLD_LEFT)
            runCurrent()
            situations.situation.value = CallSituation.ON_CALL
            presses.press(HandlebarSwitch.HOLD_LEFT)
            runCurrent()
            situations.situation.value = CallSituation.RIDING
            presses.press(HandlebarSwitch.HOLD_LEFT)
            runCurrent()

            assertThat(performed).containsExactly(RiderAction.DECLINE_CALL, RiderAction.END_CALL, RiderAction.SIRI)
        }

    @Test
    fun `a press uses the rider's choice for the current situation, end call on any press`() =
        runTest {
            val presses = presses()
            mapping.assign(CallSituation.ON_CALL, HandlebarSwitch.RIGHT, RiderAction.END_CALL)
            situations.situation.value = CallSituation.ON_CALL

            presses.press(HandlebarSwitch.RIGHT)
            runCurrent()

            assertThat(performed).containsExactly(RiderAction.END_CALL)
        }

    @Test
    fun `a press mapped to nothing does nothing and shows nothing`() =
        runTest {
            val presses = presses()

            presses.press(HandlebarSwitch.LEFT)
            runCurrent()

            assertThat(performed).isEmpty()
            assertThat(banner.done).isEmpty()
            assertThat(log.events("control.press")).hasSize(1)
        }

    @Test
    fun `an action CarLink cannot do is reported on the banner and logged, not crashed`() =
        runTest {
            val presses = presses(FakeControlTarget(CARLINK_SUPPORTED))

            // Hold left is Siri while riding; with CarLink it needs our own CarPlay.
            presses.press(HandlebarSwitch.HOLD_LEFT)
            runCurrent()

            assertThat(banner.unavailable).containsExactly(RiderAction.SIRI to Unavailable.NEEDS_OUR_CARPLAY)
            assertThat(banner.done).isEmpty()
            assertThat(log.events("control.unavailable")).hasSize(1)
        }

    @Test
    fun `presses keep working after an unavailable one`() =
        runTest {
            val presses = presses(FakeControlTarget(CARLINK_SUPPORTED))
            situations.situation.value = CallSituation.ON_CALL

            presses.press(HandlebarSwitch.HOLD_LEFT)
            presses.press(HandlebarSwitch.LEFT)
            runCurrent()

            assertThat(banner.unavailable).containsExactly(RiderAction.END_CALL to Unavailable.NEEDS_OUR_CARPLAY)
            assertThat(banner.done).containsExactly(RiderAction.MUTE_MIC to null)
        }

    @Test
    fun `a mute pressed on a call that ends before it runs does not mute`() =
        runTest {
            val presses = presses()
            situations.situation.value = CallSituation.ON_CALL
            runCurrent()

            // The press picks the call layer; the call is over by the time its action runs.
            presses.press(HandlebarSwitch.LEFT)
            situations.situation.value = CallSituation.RIDING
            runCurrent()

            assertThat(microphone.isMuted).isFalse()
            assertThat(banner.unavailable).containsExactly(RiderAction.MUTE_MIC to Unavailable.NO_CALL)
        }

    @Test
    fun `a mic muted from the handlebar is unmuted when the call ends`() =
        runTest {
            val presses = presses()
            situations.situation.value = CallSituation.ON_CALL
            runCurrent()

            presses.press(HandlebarSwitch.LEFT)
            runCurrent()
            assertThat(microphone.isMuted).isTrue()

            situations.situation.value = CallSituation.RIDING
            runCurrent()
            assertThat(microphone.isMuted).isFalse()
        }
}

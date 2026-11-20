// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.controls

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayCallStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayKey
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink.Control
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/**
 * The smart buttons with our own CarPlay, the only CarPlay since it replaced CarLink (docs/PLAN.md "Smart buttons"):
 * presses go to it while it is connected and do nothing (logged) otherwise, the situation comes from the iPhone's
 * iAP2 calls while connected (ringing included) and is riding otherwise, and the Handlebar page marks nothing as
 * unavailable.
 */
class OurCarPlayTest {
    private val link = FakeCarPlayLink(CarPlayState.Idle)
    private val carPlay = OurCarPlay(link)
    private val microphone = FakeMicrophone()
    private val log = RecordingLogSink()

    private fun TestScope.logger() = testLogger(log, TestTimeProvider(testScheduler))

    private fun TestScope.target() = OurCarPlayControlTarget(carPlay, logger())

    private fun TestScope.situations() = CarPlayCallSituations(carPlay, backgroundScope, logger())

    private fun connect() = link.setState(CarPlayState.Connected("iphone", Instant.EPOCH))

    @Test
    fun `while our CarPlay is connected each action goes to it as DiPlay's key or Siri`() =
        runTest {
            connect()
            val target = target()

            ControlAction.entries.forEach { assertThat(target.perform(it), "$it").isEqualTo(ControlResult.Done) }

            assertThat(link.controls).containsExactly(
                Control.Press(CarPlayKey.PLAY_PAUSE),
                Control.Press(CarPlayKey.NEXT),
                Control.Press(CarPlayKey.PREVIOUS),
                Control.Press(CarPlayKey.HOOK_SWITCH),
                Control.Press(CarPlayKey.DROP),
                Control.Press(CarPlayKey.DROP),
                Control.Siri,
            )
            assertThat(log.events("control.carplay").first().fields).containsExactly(
                "action" to ControlAction.PLAY_PAUSE,
                "key" to CarPlayKey.PLAY_PAUSE,
            )
        }

    @Test
    fun `while our CarPlay is not connected a press does nothing and is logged`() =
        runTest {
            val target = target()
            val notConnected =
                listOf(
                    CarPlayState.Stopped,
                    CarPlayState.Idle,
                    CarPlayState.Connecting(ConnectStep.PAIRING),
                    CarPlayState.Recovering(CarPlayError.DialFailed("x"), 1, Instant.EPOCH),
                )

            notConnected.forEach { state ->
                link.setState(state)
                ControlAction.entries.forEach {
                    assertThat(target.perform(it), "$state $it")
                        .isEqualTo(ControlResult.NotDone(Unavailable.NO_CARPLAY_SESSION))
                }
            }

            assertThat(link.controls).isEmpty()
            assertThat(log.events("control.carplay.not_connected").size)
                .isEqualTo(notConnected.size * ControlAction.entries.size)
        }

    @Test
    fun `without CarPlay ports our CarPlay is never connected and a press does nothing`() =
        runTest {
            val none = OurCarPlay(null)
            val target = OurCarPlayControlTarget(none, logger())

            assertThat(none.isConnected).isFalse()
            assertThat(target.perform(ControlAction.PLAY_PAUSE))
                .isEqualTo(ControlResult.NotDone(Unavailable.NO_CARPLAY_SESSION))
            assertThat(none.calls.value).isEmpty()
        }

    @Test
    fun `while connected the situation follows the iPhone's calls, ringing included`() =
        runTest {
            connect()
            val situations = situations()
            val current = situations.current
            runCurrent()
            assertThat(current.value).isEqualTo(CallSituation.RIDING)

            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.RINGING)))
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.RINGING)

            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.ACTIVE)))
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.ON_CALL)

            // Call waiting: a second call ringing over the active one wins.
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.ACTIVE), CarPlayCall(CarPlayCallStatus.RINGING)))
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.RINGING)

            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.HELD)))
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.ON_CALL)

            link.setCalls(emptyList())
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.RIDING)
            assertThat(log.events("control.situation").map { it.fields.first() }).isEqualTo(
                listOf(
                    CallSituation.RIDING,
                    CallSituation.RINGING,
                    CallSituation.ON_CALL,
                    CallSituation.RINGING,
                    CallSituation.ON_CALL,
                    CallSituation.RIDING,
                ).map { "situation" to it },
            )
        }

    @Test
    fun `while not connected the situation is riding, and it switches with the connection`() =
        runTest {
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.RINGING)))
            val situations = situations()
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.RIDING)

            connect()
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.RINGING)

            link.setState(CarPlayState.Idle)
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.RIDING)
        }

    @Test
    fun `a situation read before any collector already knows a connected ringing call`() =
        runTest {
            connect()
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.RINGING)))

            assertThat(situations().current.value).isEqualTo(CallSituation.RINGING)
        }

    @Test
    fun `availability marks nothing as unavailable, connected or not, but a microphone Android keeps`() =
        runTest {
            val availability = ActionAvailability(microphone)

            RiderAction.entries.forEach { assertThat(availability.whyUnavailable(it), "$it").isNull() }
            connect()
            RiderAction.entries.forEach { assertThat(availability.whyUnavailable(it), "$it").isNull() }

            microphone.canMuteNow = false
            assertThat(availability.whyUnavailable(RiderAction.MUTE_MIC)).isEqualTo(Unavailable.MIC_REFUSED)
            assertThat(availability.whyUnavailable(RiderAction.SIRI)).isNull()
        }

    @Test
    fun `with our CarPlay connected a ringing call is answered from the left key and declined from its hold`() =
        runTest {
            connect()
            val situations = situations()
            val logger = logger()
            val controls =
                CarPlayControls(target(), microphone, situations, FakeMicMuteRecord(), backgroundScope, logger)
            controls.start()
            val performer =
                ActionPerformer { action ->
                    when (val result = controls.perform(action)) {
                        ControlResult.Done -> ActionOutcome.Done()
                        is ControlResult.NotDone -> ActionOutcome.NotDone(result.reason)
                    }
                }
            val banner = RecordingPressFeedback()
            val presses =
                MappedButtonPresses(InMemoryHandlebarMapping(), performer, situations, banner, backgroundScope, logger)
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.RINGING)))
            runCurrent()

            presses.press(HandlebarSwitch.LEFT)
            runCurrent()
            presses.press(HandlebarSwitch.HOLD_LEFT)
            runCurrent()
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.ACTIVE)))
            runCurrent()
            presses.press(HandlebarSwitch.HOLD_LEFT)
            runCurrent()

            assertThat(link.controls).containsExactly(
                Control.Press(CarPlayKey.HOOK_SWITCH),
                Control.Press(CarPlayKey.DROP),
                Control.Press(CarPlayKey.DROP),
            )
            assertThat(banner.done.map { it.first }).containsExactly(
                RiderAction.ANSWER_CALL,
                RiderAction.DECLINE_CALL,
                RiderAction.END_CALL,
            )
            assertThat(banner.unavailable).isEmpty()
        }

    @Test
    fun `mute mic stays Android's with our CarPlay, and a call that ends on the iPhone unmutes it`() =
        runTest {
            connect()
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.ACTIVE)))
            val record = FakeMicMuteRecord()
            val controls = CarPlayControls(target(), microphone, situations(), record, backgroundScope, logger())
            controls.start()
            runCurrent()

            assertThat(controls.perform(RiderAction.MUTE_MIC)).isEqualTo(ControlResult.Done)
            assertThat(microphone.isMuted).isTrue()
            assertThat(link.controls).isEmpty()

            link.setCalls(emptyList())
            runCurrent()

            assertThat(microphone.isMuted).isFalse()
            assertThat(record.flag).isFalse()
        }

    @Test
    fun `our CarPlay disconnecting mid-call unmutes a mic we muted`() =
        runTest {
            connect()
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.ACTIVE)))
            val controls =
                CarPlayControls(target(), microphone, situations(), FakeMicMuteRecord(), backgroundScope, logger())
            controls.start()
            runCurrent()
            controls.perform(RiderAction.MUTE_MIC)

            link.setState(CarPlayState.Recovering(CarPlayError.DialFailed("x"), 1, Instant.EPOCH))
            runCurrent()

            assertThat(microphone.isMuted).isFalse()
        }
}

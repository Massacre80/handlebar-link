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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/**
 * The smart buttons with our own CarPlay (docs/PLAN.md "Smart buttons"): presses go to our CarPlay while it is
 * connected and to CarLink otherwise, the situation comes from the iPhone's iAP2 calls while connected (ringing
 * included) and from the phone's audio otherwise, and the Handlebar page's availability follows.
 */
class OurCarPlayTest {
    private val link = FakeCarPlayLink(CarPlayState.Idle)
    private val carPlay = OurCarPlay(link)
    private val carLink = FakeControlTarget(CARLINK_SUPPORTED)
    private val audio = FakeCallSituations(seesRinging = false)
    private val microphone = FakeMicrophone()
    private val log = RecordingLogSink()

    private fun TestScope.logger() = testLogger(log, TestTimeProvider(testScheduler))

    private fun TestScope.target() =
        RoutedControlTarget(carPlay, OurCarPlayControlTarget(carPlay, logger()), carLink, logger())

    private fun TestScope.situations() = RoutedCallSituations(carPlay, audio, backgroundScope, logger())

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
            assertThat(carLink.performed).isEmpty()
            assertThat(target.supported).isEqualTo(ControlAction.entries.toSet())
            assertThat(log.events("control.route").first().fields).containsExactly(
                "action" to ControlAction.PLAY_PAUSE,
                "to" to "our_carplay",
            )
        }

    @Test
    fun `while our CarPlay is not connected presses keep going to CarLink, which takes the music keys only`() =
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
                assertThat(target.perform(ControlAction.NEXT), "$state").isEqualTo(ControlResult.Done)
                assertThat(target.perform(ControlAction.ANSWER_CALL), "$state")
                    .isEqualTo(ControlResult.NotDone(Unavailable.NEEDS_OUR_CARPLAY))
                assertThat(target.supported, "$state").isEqualTo(CARLINK_SUPPORTED)
            }

            assertThat(link.controls).isEmpty()
            assertThat(carLink.performed).isEqualTo(List(notConnected.size) { ControlAction.NEXT })
            assertThat(log.events("control.route").last().fields).containsExactly(
                "action" to ControlAction.ANSWER_CALL,
                "to" to "carlink",
            )
        }

    @Test
    fun `without CarPlay ports our CarPlay is never connected and presses go to CarLink`() =
        runTest {
            val none = OurCarPlay(null)
            val target = RoutedControlTarget(none, OurCarPlayControlTarget(none, logger()), carLink, logger())

            assertThat(none.isConnected).isFalse()
            assertThat(target.perform(ControlAction.PLAY_PAUSE)).isEqualTo(ControlResult.Done)
            assertThat(carLink.performed).containsExactly(ControlAction.PLAY_PAUSE)
            assertThat(none.calls.value).isEmpty()
        }

    @Test
    fun `our CarPlay's target refuses when the connection went between the choice and the press`() =
        runTest {
            val ours = OurCarPlayControlTarget(carPlay, logger())

            assertThat(ours.perform(ControlAction.ANSWER_CALL))
                .isEqualTo(ControlResult.NotDone(Unavailable.NO_CARPLAY_SESSION))
            assertThat(link.controls).isEmpty()
        }

    @Test
    fun `while connected the situation follows the iPhone's calls, ringing included`() =
        runTest {
            connect()
            val situations = situations()
            val current = situations.current
            runCurrent()
            assertThat(current.value).isEqualTo(CallSituation.RIDING)
            assertThat(situations.seesRinging).isTrue()

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
            assertThat(log.events("control.situation.routed").map { it.fields.first() }).isEqualTo(
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
    fun `while not connected the situation is the phone's audio, never ringing, and switches with the connection`() =
        runTest {
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.RINGING)))
            val situations = situations()
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.RIDING)
            assertThat(situations.seesRinging).isFalse()

            audio.situation.value = CallSituation.ON_CALL
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.ON_CALL)

            connect()
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.RINGING)
            assertThat(log.events("control.situation.routed").last().fields).containsExactly(
                "situation" to CallSituation.RINGING,
                "source" to "our_carplay",
            )

            link.setState(CarPlayState.Idle)
            runCurrent()
            assertThat(situations.current.value).isEqualTo(CallSituation.ON_CALL)
        }

    @Test
    fun `a situation read before any collector already knows a connected ringing call`() =
        runTest {
            connect()
            link.setCalls(listOf(CarPlayCall(CarPlayCallStatus.RINGING)))

            assertThat(situations().current.value).isEqualTo(CallSituation.RINGING)
        }

    @Test
    fun `availability while connected marks nothing as needing our CarPlay, and while not connected it does`() =
        runTest {
            val availability = ActionAvailability(target(), microphone, situations(), carPlay)

            CALL_CONTROLS.keys.forEach {
                assertThat(availability.whyUnavailable(it), "$it").isEqualTo(Unavailable.NEEDS_OUR_CARPLAY)
            }
            assertThat(availability.whyUnavailable(CallSituation.RINGING)).isEqualTo(Unavailable.NEEDS_OUR_CARPLAY)

            connect()

            RiderAction.entries.forEach { assertThat(availability.whyUnavailable(it), "$it").isNull() }
            CallSituation.entries.forEach { assertThat(availability.whyUnavailable(it), "$it").isNull() }

            link.setState(CarPlayState.Idle)
            assertThat(availability.whyUnavailable(RiderAction.SIRI)).isEqualTo(Unavailable.NEEDS_OUR_CARPLAY)
        }

    @Test
    fun `availability tells the page when our CarPlay connects and goes`() =
        runTest {
            val availability = ActionAvailability(target(), microphone, situations(), carPlay)
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch(
                UnconfinedTestDispatcher(testScheduler),
            ) { availability.changes.collect { seen += it } }

            connect()
            runCurrent()
            link.setState(CarPlayState.Idle)
            runCurrent()

            assertThat(seen).containsExactly(false, true, false)
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
    fun `our CarPlay disconnecting mid-call with nothing in the audio unmutes a mic we muted`() =
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

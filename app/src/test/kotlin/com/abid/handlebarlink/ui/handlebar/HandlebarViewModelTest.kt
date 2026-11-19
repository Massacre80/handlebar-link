// UnconfinedTestDispatcher is kotlinx-coroutines-test API.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.ui.handlebar

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.controls.ActionAvailability
import com.abid.handlebarlink.controls.CARLINK_SUPPORTED
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.FakeCallSituations
import com.abid.handlebarlink.controls.FakeControlTarget
import com.abid.handlebarlink.controls.FakeMicrophone
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.InMemoryHandlebarMapping
import com.abid.handlebarlink.controls.OurCarPlay
import com.abid.handlebarlink.controls.OurCarPlayControlTarget
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.controls.RoutedCallSituations
import com.abid.handlebarlink.controls.RoutedControlTarget
import com.abid.handlebarlink.controls.Unavailable
import com.abid.handlebarlink.controls.allowedActions
import com.abid.handlebarlink.core.testing.MainDispatcherRule
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HandlebarViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val mapping = InMemoryHandlebarMapping()

    /** What CarLink can do today: the music keys only, no ringing seen. */
    private val carLink =
        ActionAvailability(
            FakeControlTarget(CARLINK_SUPPORTED),
            FakeMicrophone(),
            FakeCallSituations(seesRinging = false),
            OurCarPlay(null),
        )

    /** Our own CarPlay: everything, ringing included. */
    private val ourCarPlay =
        ActionAvailability(FakeControlTarget(), FakeMicrophone(), FakeCallSituations(), OurCarPlay(null))

    /** A view model whose page is collected (as the screen does), so [HandlebarViewModel.uiState] is live. */
    private fun TestScope.viewModel(availability: ActionAvailability = carLink): HandlebarViewModel {
        val viewModel = HandlebarViewModel(mapping, availability)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `the page opens on riding, with the riding presses`() =
        runTest {
            val state = viewModel().uiState.value

            assertThat(state.situation).isEqualTo(CallSituation.RIDING)
            assertThat(state.presses.mapValues { it.value.action })
                .isEqualTo(HANDLEBAR_DEFAULTS.getValue(CallSituation.RIDING))
        }

    @Test
    fun `choosing a situation shows that situation's presses`() =
        runTest {
            val viewModel = viewModel()

            viewModel.select(CallSituation.RINGING)
            assertThat(viewModel.uiState.value.situation).isEqualTo(CallSituation.RINGING)
            assertThat(
                viewModel.uiState.value
                    .press(HandlebarSwitch.LEFT)
                    .action,
            ).isEqualTo(RiderAction.ANSWER_CALL)
            assertThat(
                viewModel.uiState.value
                    .press(HandlebarSwitch.HOLD_LEFT)
                    .action,
            ).isEqualTo(RiderAction.DECLINE_CALL)

            viewModel.select(CallSituation.ON_CALL)
            assertThat(
                viewModel.uiState.value
                    .press(HandlebarSwitch.LEFT)
                    .action,
            ).isEqualTo(RiderAction.MUTE_MIC)
            assertThat(
                viewModel.uiState.value
                    .press(HandlebarSwitch.UP)
                    .action,
            ).isEqualTo(RiderAction.VOLUME_UP)
        }

    @Test
    fun `assigning writes the chosen situation's layer only, and the page shows it`() =
        runTest {
            val viewModel = viewModel()
            viewModel.select(CallSituation.ON_CALL)

            val stored = viewModel.assignNow(CallSituation.ON_CALL, HandlebarSwitch.RIGHT, RiderAction.BRIGHTNESS_UP)

            assertThat(stored).isTrue()
            val all = mapping.current.value
            assertThat(all.getValue(CallSituation.ON_CALL)[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.BRIGHTNESS_UP)
            assertThat(all.getValue(CallSituation.RIDING)[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.PLAY_PAUSE)
            assertThat(all.getValue(CallSituation.RINGING)[HandlebarSwitch.RIGHT]).isEqualTo(RiderAction.NOTHING)
            assertThat(
                viewModel.uiState.value
                    .press(HandlebarSwitch.RIGHT)
                    .action,
            ).isEqualTo(RiderAction.BRIGHTNESS_UP)
            assertThat(viewModel.uiState.value.atDefaults).isFalse()
        }

    @Test
    fun `an action the situation does not offer is refused and nothing changes`() =
        runTest {
            val viewModel = viewModel()

            val stored = viewModel.assignNow(CallSituation.ON_CALL, HandlebarSwitch.LEFT, RiderAction.NEXT)

            assertThat(stored).isFalse()
            assertThat(mapping.current.value).isEqualTo(HANDLEBAR_DEFAULTS)
        }

    @Test
    fun `an action that needs our own CarPlay may be chosen and is then marked soon`() =
        runTest {
            val viewModel = viewModel()
            viewModel.select(CallSituation.ON_CALL)

            assertThat(viewModel.assignNow(CallSituation.ON_CALL, HandlebarSwitch.HOLD_DOWN, RiderAction.END_CALL))
                .isTrue()

            val press = viewModel.uiState.value.press(HandlebarSwitch.HOLD_DOWN)
            assertThat(press.unavailable).isEqualTo(Unavailable.NEEDS_OUR_CARPLAY)
            assertThat(press.soon).isTrue()
        }

    @Test
    fun `with CarLink, Siri is marked soon with its reason and the music keys work`() =
        runTest {
            val state = viewModel().uiState.value

            val siri = state.press(HandlebarSwitch.HOLD_LEFT)
            assertThat(siri.action).isEqualTo(RiderAction.SIRI)
            assertThat(siri.unavailable).isEqualTo(Unavailable.NEEDS_OUR_CARPLAY)
            assertThat(siri.soon).isTrue()
            assertThat(state.press(HandlebarSwitch.UP).unavailable).isNull()
            assertThat(state.press(HandlebarSwitch.UP).soon).isFalse()
            assertThat(state.notice).isNull()
        }

    @Test
    fun `with CarLink, ringing says it needs our CarPlay and the call keys are marked soon`() =
        runTest {
            val viewModel = viewModel()

            viewModel.select(CallSituation.RINGING)

            val state = viewModel.uiState.value
            assertThat(state.notice).isEqualTo(HandlebarNotice.RINGING_NEEDS_OUR_CARPLAY)
            assertThat(state.press(HandlebarSwitch.LEFT).soon).isTrue()
            assertThat(state.press(HandlebarSwitch.HOLD_LEFT).soon).isTrue()
            assertThat(state.press(HandlebarSwitch.UP).soon).isFalse()
        }

    @Test
    fun `with CarLink, on a call says hanging up needs our CarPlay while mute works`() =
        runTest {
            val viewModel = viewModel()

            viewModel.select(CallSituation.ON_CALL)

            val state = viewModel.uiState.value
            assertThat(state.notice).isEqualTo(HandlebarNotice.HANG_UP_NEEDS_OUR_CARPLAY)
            assertThat(state.press(HandlebarSwitch.HOLD_LEFT).soon).isTrue()
            assertThat(state.press(HandlebarSwitch.LEFT).unavailable).isNull()
        }

    @Test
    fun `the page follows our CarPlay connecting and going, the ringing notice and soon tags with it`() =
        runTest {
            val link = FakeCarPlayLink(CarPlayState.Idle)
            val carPlay = OurCarPlay(link)
            val logger = testLogger(RecordingLogSink(), TestTimeProvider(testScheduler))
            val availability =
                ActionAvailability(
                    RoutedControlTarget(
                        carPlay,
                        OurCarPlayControlTarget(carPlay, logger),
                        FakeControlTarget(CARLINK_SUPPORTED),
                        logger,
                    ),
                    FakeMicrophone(),
                    RoutedCallSituations(carPlay, FakeCallSituations(seesRinging = false), backgroundScope, logger),
                    carPlay,
                )
            val viewModel = viewModel(availability)
            viewModel.select(CallSituation.RINGING)
            assertThat(viewModel.uiState.value.notice).isEqualTo(HandlebarNotice.RINGING_NEEDS_OUR_CARPLAY)
            assertThat(
                viewModel.uiState.value
                    .press(HandlebarSwitch.LEFT)
                    .soon,
            ).isTrue()

            link.setState(CarPlayState.Connected("iphone", Instant.EPOCH))

            assertThat(viewModel.uiState.value.notice).isNull()
            assertThat(
                viewModel.uiState.value
                    .press(HandlebarSwitch.LEFT)
                    .soon,
            ).isFalse()
            val picker = viewModel.picker(CallSituation.RIDING, HandlebarSwitch.HOLD_LEFT).first()
            assertThat(picker.choices.filter { it.unavailable != null }).isEmpty()

            link.setState(CarPlayState.Idle)

            assertThat(viewModel.uiState.value.notice).isEqualTo(HandlebarNotice.RINGING_NEEDS_OUR_CARPLAY)
        }

    @Test
    fun `with our own CarPlay nothing is marked and no notice shows`() =
        runTest {
            val viewModel = viewModel(ourCarPlay)

            CallSituation.entries.forEach { situation ->
                viewModel.select(situation)
                val state = viewModel.uiState.value
                assertThat(state.notice).isNull()
                assertThat(state.presses.values.filter { it.unavailable != null }).isEmpty()
            }
        }

    @Test
    fun `a microphone Android will not change is shown with that reason, not as soon`() =
        runTest {
            val refusing =
                ActionAvailability(
                    FakeControlTarget(CARLINK_SUPPORTED),
                    FakeMicrophone(canMuteNow = false),
                    FakeCallSituations(seesRinging = false),
                    OurCarPlay(null),
                )
            val viewModel = viewModel(refusing)

            viewModel.select(CallSituation.ON_CALL)

            val mute = viewModel.uiState.value.press(HandlebarSwitch.LEFT)
            assertThat(mute.unavailable).isEqualTo(Unavailable.MIC_REFUSED)
            assertThat(mute.soon).isFalse()
        }

    @Test
    fun `the picker lists each situation's own choices, in order, with what works today`() =
        runTest {
            val viewModel = viewModel()

            CallSituation.entries.forEach { situation ->
                val picker = viewModel.picker(situation, HandlebarSwitch.HOLD_LEFT).first()
                assertThat(picker.situation).isEqualTo(situation)
                assertThat(picker.choices.map { it.action }).isEqualTo(allowedActions(situation))
            }
            val ringing = viewModel.picker(CallSituation.RINGING, HandlebarSwitch.LEFT).first()
            assertThat(ringing.choices.map { it.action }).doesNotContain(RiderAction.NEXT)
            assertThat(ringing.choices.first { it.action == RiderAction.ANSWER_CALL }.soon).isTrue()
            val riding = viewModel.picker(CallSituation.RIDING, HandlebarSwitch.LEFT).first()
            assertThat(riding.choices.map { it.action }).doesNotContain(RiderAction.END_CALL)
            assertThat(riding.choices.first { it.action == RiderAction.SIRI }.soon).isTrue()
            assertThat(riding.choices.first { it.action == RiderAction.NEXT }.soon).isFalse()
        }

    @Test
    fun `the picker marks the press's action in its own situation`() =
        runTest {
            val viewModel = viewModel()
            viewModel.assignNow(CallSituation.ON_CALL, HandlebarSwitch.HOLD_LEFT, RiderAction.MUTE_MIC)

            assertThat(viewModel.picker(CallSituation.ON_CALL, HandlebarSwitch.HOLD_LEFT).first().chosen)
                .isEqualTo(RiderAction.MUTE_MIC)
            assertThat(viewModel.pickerNow(CallSituation.RIDING, HandlebarSwitch.HOLD_LEFT).chosen)
                .isEqualTo(RiderAction.SIRI)
            assertThat(viewModel.pickerNow(CallSituation.RINGING, HandlebarSwitch.HOLD_LEFT).chosen)
                .isEqualTo(RiderAction.DECLINE_CALL)
        }

    @Test
    fun `reset puts every situation back to its defaults`() =
        runTest {
            val viewModel = viewModel()
            viewModel.assignNow(CallSituation.RIDING, HandlebarSwitch.UP, RiderAction.TIME)
            viewModel.assignNow(CallSituation.ON_CALL, HandlebarSwitch.LEFT, RiderAction.NOTHING)

            viewModel.resetNow()

            assertThat(mapping.current.value).isEqualTo(HANDLEBAR_DEFAULTS)
            assertThat(viewModel.uiState.value.atDefaults).isTrue()
        }

    @Test
    fun `while a call rings or is on, only keys that do something are called out, the call key in brand`() {
        val ringing = handlebarState(CallSituation.RINGING, HANDLEBAR_DEFAULTS, carLink)
        val onCall = handlebarState(CallSituation.ON_CALL, HANDLEBAR_DEFAULTS, carLink)
        val riding = handlebarState(CallSituation.RIDING, HANDLEBAR_DEFAULTS, carLink)

        assertThat(keyCallouts(CallSituation.RINGING) { ringing.press(it).action })
            .containsExactly(KeyCallout(SwitchKey.LEFT, RiderAction.ANSWER_CALL, RiderAction.DECLINE_CALL, call = true))
        assertThat(keyCallouts(CallSituation.ON_CALL) { onCall.press(it).action }).containsExactly(
            KeyCallout(SwitchKey.UP, RiderAction.VOLUME_UP),
            KeyCallout(SwitchKey.DOWN, RiderAction.VOLUME_DOWN),
            KeyCallout(SwitchKey.LEFT, RiderAction.MUTE_MIC, RiderAction.END_CALL, call = true),
        )
        assertThat(keyCallouts(CallSituation.RIDING) { riding.press(it).action }.map { it.key })
            .isEqualTo(SwitchKey.entries)
    }
}

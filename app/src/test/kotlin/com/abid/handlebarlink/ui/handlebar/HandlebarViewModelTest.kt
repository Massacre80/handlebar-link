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
import com.abid.handlebarlink.controls.ActionAvailability
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.FakeMicrophone
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.InMemoryHandlebarMapping
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.controls.Unavailable
import com.abid.handlebarlink.controls.allowedActions
import com.abid.handlebarlink.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class HandlebarViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val mapping = InMemoryHandlebarMapping()

    /** Our own CarPlay: every action and situation works. */
    private val ourCarPlay = ActionAvailability(FakeMicrophone())

    /** A view model whose page is collected (as the screen does), so [HandlebarViewModel.uiState] is live. */
    private fun TestScope.viewModel(availability: ActionAvailability = ourCarPlay): HandlebarViewModel {
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
    fun `a call action may be chosen for another key and works there`() =
        runTest {
            val viewModel = viewModel()
            viewModel.select(CallSituation.ON_CALL)

            assertThat(viewModel.assignNow(CallSituation.ON_CALL, HandlebarSwitch.HOLD_DOWN, RiderAction.END_CALL))
                .isTrue()

            val press = viewModel.uiState.value.press(HandlebarSwitch.HOLD_DOWN)
            assertThat(press.action).isEqualTo(RiderAction.END_CALL)
            assertThat(press.unavailable).isNull()
        }

    @Test
    fun `with our own CarPlay nothing is marked in any situation, Siri and the call keys included`() =
        runTest {
            val viewModel = viewModel(ourCarPlay)

            CallSituation.entries.forEach { situation ->
                viewModel.select(situation)
                val state = viewModel.uiState.value
                assertThat(state.presses.values.filter { it.unavailable != null }).isEmpty()
            }
            val picker = viewModel.picker(CallSituation.RIDING, HandlebarSwitch.HOLD_LEFT).first()
            assertThat(picker.choices.filter { it.unavailable != null }).isEmpty()
        }

    @Test
    fun `a microphone Android will not change is shown with that reason`() =
        runTest {
            val viewModel = viewModel(ActionAvailability(FakeMicrophone(canMuteNow = false)))

            viewModel.select(CallSituation.ON_CALL)

            val mute = viewModel.uiState.value.press(HandlebarSwitch.LEFT)
            assertThat(mute.unavailable).isEqualTo(Unavailable.MIC_REFUSED)
            assertThat(
                viewModel.uiState.value
                    .press(HandlebarSwitch.HOLD_LEFT)
                    .unavailable,
            ).isNull()
        }

    @Test
    fun `the picker lists each situation's own choices, in order`() =
        runTest {
            val viewModel = viewModel()

            CallSituation.entries.forEach { situation ->
                val picker = viewModel.picker(situation, HandlebarSwitch.HOLD_LEFT).first()
                assertThat(picker.situation).isEqualTo(situation)
                assertThat(picker.choices.map { it.action }).isEqualTo(allowedActions(situation))
            }
            val ringing = viewModel.picker(CallSituation.RINGING, HandlebarSwitch.LEFT).first()
            assertThat(ringing.choices.map { it.action }).doesNotContain(RiderAction.NEXT)
            val riding = viewModel.picker(CallSituation.RIDING, HandlebarSwitch.LEFT).first()
            assertThat(riding.choices.map { it.action }).doesNotContain(RiderAction.END_CALL)
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
        val ringing = handlebarState(CallSituation.RINGING, HANDLEBAR_DEFAULTS, ourCarPlay)
        val onCall = handlebarState(CallSituation.ON_CALL, HANDLEBAR_DEFAULTS, ourCarPlay)
        val riding = handlebarState(CallSituation.RIDING, HANDLEBAR_DEFAULTS, ourCarPlay)

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

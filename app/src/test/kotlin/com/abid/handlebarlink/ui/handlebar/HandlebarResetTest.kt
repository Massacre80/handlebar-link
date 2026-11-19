package com.abid.handlebarlink.ui.handlebar

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.ActionAvailability
import com.abid.handlebarlink.controls.CARLINK_SUPPORTED
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.FakeCallSituations
import com.abid.handlebarlink.controls.FakeControlTarget
import com.abid.handlebarlink.controls.FakeMicrophone
import com.abid.handlebarlink.controls.HANDLEBAR_DEFAULTS
import com.abid.handlebarlink.controls.InMemoryHandlebarMapping
import com.abid.handlebarlink.controls.OurCarPlay
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.testing.MainDispatcherRule
import com.abid.handlebarlink.ui.navigation.HandlebarResetRoute
import com.abid.handlebarlink.ui.navigation.HandlebarRoute
import com.abid.handlebarlink.ui.navigation.NavigationState
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Reset to default on the Handlebar page: the button only asks; the dialog's confirm resets. */
class HandlebarResetTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val mapping = InMemoryHandlebarMapping()
    private val state =
        NavigationState(
            TOP_LEVEL_DESTINATIONS.associate { it.route to NavBackStack<NavKey>(it.route) },
            mutableIntStateOf(0),
        )
    private val navigator = Navigator(state).also { it.navigate(HandlebarRoute) }

    private fun viewModel() =
        HandlebarViewModel(
            mapping,
            ActionAvailability(
                FakeControlTarget(CARLINK_SUPPORTED),
                FakeMicrophone(),
                FakeCallSituations(),
                OurCarPlay(null),
            ),
        )

    /** The rider has changed a press in two situations. */
    private suspend fun changed() {
        mapping.assign(CallSituation.RIDING, HandlebarSwitch.UP, RiderAction.TIME)
        mapping.assign(CallSituation.ON_CALL, HandlebarSwitch.LEFT, RiderAction.NOTHING)
        assertThat(mapping.current.value).isNotEqualTo(HANDLEBAR_DEFAULTS)
    }

    @Test
    fun `the page's reset button only opens the confirmation, nothing is reset by it`() =
        runTest {
            changed()
            val before = mapping.current.value

            askToReset(navigator)

            assertThat(state.current).isEqualTo(HandlebarResetRoute)
            assertThat(mapping.current.value).isEqualTo(before)
        }

    @Test
    fun `confirming resets every situation and closes the dialog, back on the page`() =
        runTest {
            changed()
            askToReset(navigator)

            confirmReset(viewModel(), navigator)

            assertThat(mapping.current.value).isEqualTo(HANDLEBAR_DEFAULTS)
            assertThat(state.current).isEqualTo(HandlebarRoute)
        }

    @Test
    fun `cancel or Back closes the dialog and keeps the rider's buttons`() =
        runTest {
            changed()
            val before = mapping.current.value
            askToReset(navigator)

            navigator.goBack()

            assertThat(state.current).isEqualTo(HandlebarRoute)
            assertThat(mapping.current.value).isEqualTo(before)
        }
}

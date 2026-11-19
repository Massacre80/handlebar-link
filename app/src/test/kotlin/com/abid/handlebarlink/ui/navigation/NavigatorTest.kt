package com.abid.handlebarlink.ui.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import org.junit.Test

/** The rules of the Navigation 3 "multiple back stacks" recipe and the navigation principles. */
class NavigatorTest {
    private val state =
        NavigationState(
            TOP_LEVEL_DESTINATIONS.associate { it.route to NavBackStack<NavKey>(it.route) },
            mutableIntStateOf(0),
        )
    private val navigator = Navigator(state)

    @Test
    fun `the app starts on Home, the fixed start destination`() {
        assertThat(state.topLevelRoute).isEqualTo(HomeRoute)
        assertThat(state.routesInUse).containsExactly(HomeRoute)
    }

    @Test
    fun `a tab shows on top of Home, and switching tabs replaces it (exit through Home)`() {
        navigator.navigate(RidesRoute)
        assertThat(state.routesInUse).containsExactly(HomeRoute, RidesRoute)

        navigator.navigate(GarageRoute)

        assertThat(state.routesInUse).containsExactly(HomeRoute, GarageRoute)
        assertThat(state.current).isEqualTo(GarageRoute)
    }

    @Test
    fun `Back from a tab's root goes to Home, not to the previous tab`() {
        navigator.navigate(RidesRoute)
        navigator.navigate(GarageRoute)

        navigator.goBack()

        assertThat(state.topLevelRoute).isEqualTo(HomeRoute)
        assertThat(state.routesInUse).containsExactly(HomeRoute)
    }

    @Test
    fun `Settings opens on Home's stack and Back closes it`() {
        navigator.navigate(SettingsRoute)
        assertThat(state.current).isEqualTo(SettingsRoute)
        assertThat(state.topLevelRoute).isEqualTo(HomeRoute)

        navigator.goBack()

        assertThat(state.current).isEqualTo(HomeRoute)
    }

    @Test
    fun `the CarPlay display opens over the CarPlay tab without the tab bar, and Back returns to the tab`() {
        navigator.navigate(CarPlayRoute)
        navigator.navigate(CarPlayDisplayRoute)
        assertThat(state.currentPage).isEqualTo(CarPlayDisplayRoute)
        assertThat(state.currentPage in TOP_LEVEL_DESTINATIONS.map { it.route }).isFalse()
        assertThat(navigator.backMove()).isEqualTo(Move.POP)

        navigator.goBack()

        assertThat(state.topLevelRoute).isEqualTo(CarPlayRoute)
        assertThat(state.current).isEqualTo(CarPlayRoute)
    }

    @Test
    fun `a tab keeps its own history while another tab is shown`() {
        navigator.navigate(SettingsRoute)
        navigator.navigate(IntercomRoute)

        navigator.navigate(HomeRoute)

        assertThat(state.current).isEqualTo(SettingsRoute)
    }

    @Test
    fun `tabs slide by their order, pages open forward and close back`() {
        navigator.navigate(GarageRoute)
        assertThat(state.lastMove).isEqualTo(Move.TAB_FORWARD)
        navigator.navigate(RidesRoute)
        assertThat(state.lastMove).isEqualTo(Move.TAB_BACKWARD)
        navigator.goBack()
        assertThat(state.lastMove).isEqualTo(Move.TAB_BACKWARD)

        navigator.navigate(SettingsRoute)
        assertThat(state.lastMove).isEqualTo(Move.PUSH)
        navigator.goBack()
        assertThat(state.lastMove).isEqualTo(Move.POP)
    }

    @Test
    fun `the back gesture previews the same move Back makes, without changing anything`() {
        navigator.navigate(SettingsRoute)
        assertThat(navigator.backMove()).isEqualTo(Move.POP)
        assertThat(state.current).isEqualTo(SettingsRoute)

        navigator.goBack()
        navigator.navigate(IntercomRoute)

        assertThat(navigator.backMove()).isEqualTo(Move.TAB_BACKWARD)
        assertThat(state.current).isEqualTo(IntercomRoute)
    }

    @Test
    fun `tapping the selected tab moves nothing`() {
        navigator.navigate(GarageRoute)
        navigator.navigate(GarageRoute)

        assertThat(state.routesInUse).containsExactly(HomeRoute, GarageRoute)
        assertThat(state.lastMove).isEqualTo(Move.TAB_FORWARD)
    }
}

package com.abid.handlebarlink.ui.navigation

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.abid.handlebarlink.R
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
    fun `the tabs are Home, CarPlay, Garage and Devices, in that order (Rides dropped)`() {
        assertThat(TOP_LEVEL_DESTINATIONS.map { it.route })
            .containsExactly(HomeRoute, CarPlayRoute, GarageRoute, IntercomRoute)
        assertThat(TOP_LEVEL_DESTINATIONS.map { it.label })
            .containsExactly(R.string.tab_home, R.string.tab_carplay, R.string.tab_garage, R.string.tab_devices)
        assertThat(tabIndex(GarageRoute)).isEqualTo(2)
        assertThat(tabIndex(SettingsRoute)).isEqualTo(-1)
    }

    @Test
    fun `the app starts on Home, the fixed start destination`() {
        assertThat(state.topLevelRoute).isEqualTo(HomeRoute)
        assertThat(state.routesInUse).containsExactly(HomeRoute)
    }

    @Test
    fun `a tab shows on top of Home, and switching tabs replaces it (exit through Home)`() {
        navigator.navigate(CarPlayRoute)
        assertThat(state.routesInUse).containsExactly(HomeRoute, CarPlayRoute)

        navigator.navigate(GarageRoute)

        assertThat(state.routesInUse).containsExactly(HomeRoute, GarageRoute)
        assertThat(state.current).isEqualTo(GarageRoute)
    }

    @Test
    fun `Back from a tab's root goes to Home, not to the previous tab`() {
        navigator.navigate(CarPlayRoute)
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
        navigator.navigate(CarPlayRoute)
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

    @Test
    fun `Open CarPlay from the handlebar shows the CarPlay display on the CarPlay tab, once`() {
        navigator.navigate(SettingsRoute)

        navigator.openCarPlayDisplay()
        navigator.openCarPlayDisplay()

        assertThat(state.topLevelRoute).isEqualTo(CarPlayRoute)
        assertThat(state.stack(CarPlayRoute).toList()).containsExactly(CarPlayRoute, CarPlayDisplayRoute)
        // Back from it returns to the CarPlay tab.
        navigator.goBack()
        assertThat(state.current).isEqualTo(CarPlayRoute)
    }

    @Test
    fun `Open CarPlay closes a sheet or dialog over the CarPlay tab first`() {
        navigator.navigate(CarPlayRoute)
        navigator.navigate(PairIphoneRoute)
        navigator.navigate(CarPlayScreenRoute)

        navigator.openCarPlayDisplay()

        assertThat(state.stack(CarPlayRoute).toList())
            .containsExactly(CarPlayRoute, PairIphoneRoute, CarPlayDisplayRoute)
    }

    @Test
    fun `Switch screen goes to the CarPlay display and back from it`() {
        navigator.navigate(IntercomRoute)

        navigator.switchScreen()
        assertThat(state.current).isEqualTo(CarPlayDisplayRoute)

        navigator.switchScreen()
        assertThat(state.current).isEqualTo(CarPlayRoute)

        navigator.switchScreen()
        assertThat(state.current).isEqualTo(CarPlayDisplayRoute)
    }
}

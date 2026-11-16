package com.abid.handlebarlink.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

/**
 * The app's navigation state, after the Navigation 3 "multiple back stacks" recipe: one back stack per
 * tab, the selected tab, and "exit through Home" (Home's stack always stays under the current tab).
 */
class NavigationState(
    /** Every tab's back stack, keyed by the tab's root route, in tab order; the first is Home. */
    val backStacks: Map<NavKey, NavBackStack<NavKey>>,
    private val selected: MutableIntState,
) {
    private val roots = backStacks.keys.toList()

    /** The start destination's route (Home). */
    val startRoute: NavKey get() = roots.first()

    /** The selected tab's root route. */
    var topLevelRoute: NavKey
        get() = roots[selected.intValue]
        set(value) {
            selected.intValue = roots.indexOf(value).also { require(it >= 0) { "$value is not a tab" } }
        }

    /** The screen on top. */
    val current: NavKey get() = stack(topLevelRoute).last()

    /** The tabs whose entries are shown: Home, and the selected tab on top of it. */
    val routesInUse: List<NavKey>
        get() = if (topLevelRoute == startRoute) listOf(startRoute) else listOf(startRoute, topLevelRoute)

    internal fun stack(route: NavKey): NavBackStack<NavKey> = backStacks[route] ?: error("no back stack for $route")
}

/** Changes [state] for the rider's taps and Back. */
class Navigator(
    private val state: NavigationState,
) {
    /** A tab root switches tabs (keeping that tab's history); anything else opens on the current tab. */
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            state.topLevelRoute = route
        } else {
            state.stack(state.topLevelRoute).add(route)
        }
    }

    /** Back: pop within the tab; from a tab's root go to Home. Home's root is left to the system (exit). */
    fun goBack() {
        val stack = state.stack(state.topLevelRoute)
        if (stack.last() == state.topLevelRoute) {
            state.topLevelRoute = state.startRoute
        } else {
            stack.removeLastOrNull()
        }
    }
}

/** Remembers the navigation state across configuration changes and process death. */
@Composable
fun rememberNavigationState(destinations: List<TopLevelDestination>): NavigationState {
    val selected = rememberSaveable { mutableIntStateOf(0) }
    val stacks = destinations.associate { it.route to rememberNavBackStack(it.route) }
    return remember(stacks, selected) { NavigationState(stacks, selected) }
}

/**
 * The entries to show: each tab's entries keep their own saved UI state and ViewModels (Navigation 3
 * "Save and manage navigation state"), and only the tabs in use are shown.
 */
@Composable
fun NavigationState.toDecoratedEntries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
    val decorated =
        backStacks.mapValues { (_, stack) ->
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                entryProvider = entryProvider,
            )
        }
    return routesInUse.flatMap { decorated[it].orEmpty() }
}

package com.abid.handlebarlink.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

    /** How the last navigation moved, for the page transition (not saved: only the next animation needs it). */
    var lastMove: Move by mutableStateOf(Move.TAB_FORWARD)
        internal set

    /** Whether the screen on top is its tab's root (Back then goes to Home). */
    internal val atTabRoot: Boolean get() = current == topLevelRoute

    internal fun index(route: NavKey): Int = roots.indexOf(route)

    internal fun stack(route: NavKey): NavBackStack<NavKey> = backStacks[route] ?: error("no back stack for $route")
}

/** The kinds of navigation, each with its own page transition (ui/navigation/PageMotion.kt). */
enum class Move {
    /** To a tab further right. */
    TAB_FORWARD,

    /** To a tab further left, including Back to Home. */
    TAB_BACKWARD,

    /** A page opened on the current tab (Settings). */
    PUSH,

    /** That page closed. */
    POP,
}

/** Changes [state] for the rider's taps and Back. */
class Navigator(
    private val state: NavigationState,
) {
    /** A tab root switches tabs (keeping that tab's history); anything else opens on the current tab. */
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            if (route == state.topLevelRoute) return
            state.lastMove =
                if (state.index(route) > state.index(state.topLevelRoute)) Move.TAB_FORWARD else Move.TAB_BACKWARD
            state.topLevelRoute = route
        } else {
            state.lastMove = Move.PUSH
            state.stack(state.topLevelRoute).add(route)
        }
    }

    /**
     * Back past [route]: pops the current tab down to the page under its last [route] (after saving a device,
     * both the sheet and Add device close). Only one page when [route] is not open; never the tab's root.
     */
    fun goBackPast(route: NavKey) {
        val stack = state.stack(state.topLevelRoute)
        val index = stack.lastIndexOf(route)
        state.lastMove = Move.POP
        if (index <= 0) {
            stack.removeLastOrNull()
            return
        }
        while (stack.size > index) stack.removeAt(stack.lastIndex)
    }

    /** What Back would do now, for the back gesture's preview (changes nothing). */
    fun backMove(): Move = if (state.atTabRoot) Move.TAB_BACKWARD else Move.POP

    /** Back: pop within the tab; from a tab's root go to Home. Home's root is left to the system (exit). */
    fun goBack() {
        state.lastMove = backMove()
        if (state.atTabRoot) {
            state.topLevelRoute = state.startRoute
        } else {
            state.stack(state.topLevelRoute).removeLastOrNull()
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

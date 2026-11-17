package com.abid.handlebarlink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.abid.handlebarlink.MainViewModel
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlTab
import com.abid.handlebarlink.core.designsystem.component.HlTabBar
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ride.RideService
import com.abid.handlebarlink.ui.home.HomeScreen
import com.abid.handlebarlink.ui.home.HomeViewModel
import com.abid.handlebarlink.ui.later.LaterScreen
import com.abid.handlebarlink.ui.navigation.AppearanceRoute
import com.abid.handlebarlink.ui.navigation.CarPlayRoute
import com.abid.handlebarlink.ui.navigation.GarageRoute
import com.abid.handlebarlink.ui.navigation.HomeRoute
import com.abid.handlebarlink.ui.navigation.IntercomRoute
import com.abid.handlebarlink.ui.navigation.NavigationState
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.RidesRoute
import com.abid.handlebarlink.ui.navigation.SettingsRoute
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import com.abid.handlebarlink.ui.navigation.pageTransition
import com.abid.handlebarlink.ui.navigation.rememberNavigationState
import com.abid.handlebarlink.ui.navigation.toDecoratedEntries
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.rememberLocationAccess
import com.abid.handlebarlink.ui.permissions.rememberMediaAccess
import com.abid.handlebarlink.ui.permissions.rememberNearbyAccess
import com.abid.handlebarlink.ui.permissions.rememberNotificationAccess
import com.abid.handlebarlink.ui.settings.AppearanceScreen
import com.abid.handlebarlink.ui.settings.SettingsAccess
import com.abid.handlebarlink.ui.settings.SettingsActions
import com.abid.handlebarlink.ui.settings.SettingsScreen
import com.abid.handlebarlink.ui.settings.SettingsViewModel
import com.abid.handlebarlink.ui.setup.SetupRoute

/**
 * The app's frame: the background, then first-time setup until a bike is saved (docs/PLAN.md "First-time
 * setup"), and the tabs after that. While the setting loads (a few milliseconds) only the background shows.
 */
@Composable
fun AppRoot() {
    val main: MainViewModel = hiltViewModel()
    val bikeChosen by main.bikeChosen.collectAsStateWithLifecycle()
    val skipped by main.setupSkipped.collectAsStateWithLifecycle()
    // Skipping setup (temporary) shows the tabs without a bike; the ride still needs one.
    val showTabs = if (skipped) true else bikeChosen
    HlBackground(Modifier.fillMaxSize()) {
        Crossfade(showTabs, animationSpec = HlTheme.motion.fade(), label = "setup or tabs") { tabs ->
            when (tabs) {
                null -> {}

                false -> {
                    SetupRoute()
                }

                true -> {
                    if (bikeChosen == true) RideAutoStart(main)
                    Tabs()
                }
            }
        }
    }
}

/**
 * The tabs: the pages (one Navigation 3 back stack per tab) and the floating tab bar, shown on the tabs'
 * pages and hidden on full pages such as Settings.
 */
@Composable
private fun BoxScope.Tabs() {
    val state = rememberNavigationState(TOP_LEVEL_DESTINATIONS)
    val navigator = remember(state) { Navigator(state) }
    val spacing = HlTheme.spacing
    // Pages leave room for the floating bar: its height, its 20 dp gap and the navigation bar.
    val barSpace =
        PaddingValues(
            bottom =
                HlTheme.sizes.tabBar + spacing.s20 * 2 +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        )
    val entries =
        entryProvider {
            entry<HomeRoute> {
                val viewModel: HomeViewModel = hiltViewModel()
                val home by viewModel.uiState.collectAsStateWithLifecycle()
                HomeScreen(home, onSettings = { navigator.navigate(SettingsRoute) }, contentPadding = barSpace)
            }
            entry<RidesRoute> { LaterScreen(HlIcons.Rides, R.string.tab_rides, R.string.later_rides, barSpace) }
            entry<CarPlayRoute> { LaterScreen(HlIcons.CarPlay, R.string.tab_carplay, R.string.later_carplay, barSpace) }
            entry<GarageRoute> { LaterScreen(HlIcons.Garage, R.string.tab_garage, R.string.later_garage, barSpace) }
            entry<IntercomRoute> {
                LaterScreen(
                    HlIcons.Intercom,
                    R.string.tab_intercom,
                    R.string.later_intercom,
                    barSpace,
                )
            }
            entry<SettingsRoute> {
                SettingsEntry(onAppearance = { navigator.navigate(AppearanceRoute) }, onBack = navigator::goBack)
            }
            entry<AppearanceRoute> {
                val viewModel: SettingsViewModel = hiltViewModel()
                val settings by viewModel.uiState.collectAsStateWithLifecycle()
                AppearanceScreen(settings.theme, viewModel::choose, onBack = navigator::goBack)
            }
        }
    val motion = HlTheme.motion
    NavDisplay(
        entries = state.toDecoratedEntries(entries),
        onBack = navigator::goBack,
        transitionSpec = { pageTransition(state.lastMove, motion) },
        popTransitionSpec = { pageTransition(state.lastMove, motion) },
        // The back gesture previews what Back will do, following the finger (Android 15+ by default).
        predictivePopTransitionSpec = { pageTransition(navigator.backMove(), motion) },
    )
    FloatingTabBar(state, navigator)
}

/** The tab bar floating over the pages; it slides away on full pages (Settings) and back on tab pages. */
@Composable
private fun BoxScope.FloatingTabBar(
    state: NavigationState,
    navigator: Navigator,
) {
    val spacing = HlTheme.spacing
    val motion = HlTheme.motion
    AnimatedVisibility(
        visible = state.current in TOP_LEVEL_DESTINATIONS.map { it.route },
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(motion.layout(IntOffset.VisibilityThreshold)) { it } + fadeIn(motion.fade()),
        exit = slideOutVertically(motion.layout(IntOffset.VisibilityThreshold)) { it } + fadeOut(motion.fade()),
    ) {
        HlTabBar(
            tabs = TOP_LEVEL_DESTINATIONS.map { HlTab(it.icon, stringResource(it.label)) },
            selected = TOP_LEVEL_DESTINATIONS.indexOfFirst { it.route == state.topLevelRoute },
            onSelect = { navigator.navigate(TOP_LEVEL_DESTINATIONS[it].route) },
            modifier =
                Modifier
                    .navigationBarsPadding()
                    .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s20)
                    .widthIn(max = HlTheme.sizes.contentMax),
        )
    }
}

/** Settings with its view model and the three permissions (re-read on every resume). */
@Composable
private fun SettingsEntry(
    onAppearance: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nearby = rememberNearbyAccess()
    val notifications = rememberNotificationAccess()
    val media = rememberMediaAccess()
    val location = rememberLocationAccess()
    // The meters can only be listed with Nearby devices; read them again whenever that or the page resumes.
    LifecycleResumeEffect(nearby.status) {
        viewModel.refreshMeters()
        onPauseOrDispose {}
    }
    SettingsScreen(
        state,
        SettingsAccess(nearby.status, notifications.status, media.status, location.status),
        SettingsActions(
            onAppearance = onAppearance,
            onBike = viewModel::choose,
            onAccess = { listOf(nearby, notifications, media, location)[it].act() },
            onSetup = viewModel::setUpAgain,
            onBack = onBack,
        ),
    )
}

/**
 * Starts the ride while the app is on screen (a foreground service may only start then): it is shown once a
 * bike is set up, and starts when Nearby devices is allowed; a running ride ignores the second start. Each
 * resume also re-reads the permission for the link (Android sends no broadcast when it changes). Waking on
 * ignition is M4.
 */
@Composable
private fun RideAutoStart(viewModel: MainViewModel) {
    val context = LocalContext.current
    val nearby = rememberNearbyAccess()
    LifecycleResumeEffect(nearby.status) {
        viewModel.onResumed()
        if (nearby.status == Access.GRANTED) RideService.start(context)
        onPauseOrDispose {}
    }
}

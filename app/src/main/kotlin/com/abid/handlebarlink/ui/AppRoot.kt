package com.abid.handlebarlink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import com.abid.handlebarlink.MainViewModel
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.ScreenRequest
import com.abid.handlebarlink.core.designsystem.component.Backdrop
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlStatusBarProtection
import com.abid.handlebarlink.core.designsystem.component.HlTab
import com.abid.handlebarlink.core.designsystem.component.HlTabBar
import com.abid.handlebarlink.core.designsystem.component.HlTabRail
import com.abid.handlebarlink.core.designsystem.component.backdropSource
import com.abid.handlebarlink.core.designsystem.component.rememberBackdrop
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ride.RideService
import com.abid.handlebarlink.startsRideOnResume
import com.abid.handlebarlink.ui.carplay.carPlayEntries
import com.abid.handlebarlink.ui.devices.ADD_DEVICE
import com.abid.handlebarlink.ui.devices.deviceEntries
import com.abid.handlebarlink.ui.frame.LivePadding
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.frame.currentWindowLayout
import com.abid.handlebarlink.ui.frame.pageSides
import com.abid.handlebarlink.ui.frame.railSpace
import com.abid.handlebarlink.ui.handlebar.handlebarEntries
import com.abid.handlebarlink.ui.home.HomeScreen
import com.abid.handlebarlink.ui.home.HomeViewModel
import com.abid.handlebarlink.ui.intercom.intercomEntries
import com.abid.handlebarlink.ui.later.LaterScreen
import com.abid.handlebarlink.ui.navigation.AddDeviceRoute
import com.abid.handlebarlink.ui.navigation.GarageRoute
import com.abid.handlebarlink.ui.navigation.HandlebarRoute
import com.abid.handlebarlink.ui.navigation.HomeRoute
import com.abid.handlebarlink.ui.navigation.IntercomRoute
import com.abid.handlebarlink.ui.navigation.LocalSharedTransitionScope
import com.abid.handlebarlink.ui.navigation.ModeRoute
import com.abid.handlebarlink.ui.navigation.NavigationState
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.PermissionsRoute
import com.abid.handlebarlink.ui.navigation.RidesRoute
import com.abid.handlebarlink.ui.navigation.SettingsRoute
import com.abid.handlebarlink.ui.navigation.SheetSceneStrategy
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import com.abid.handlebarlink.ui.navigation.ThemeRoute
import com.abid.handlebarlink.ui.navigation.pageTransition
import com.abid.handlebarlink.ui.navigation.rememberNavigationState
import com.abid.handlebarlink.ui.navigation.toDecoratedEntries
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.AccessState
import com.abid.handlebarlink.ui.permissions.rememberAllowAll
import com.abid.handlebarlink.ui.permissions.rememberBrightnessAccess
import com.abid.handlebarlink.ui.permissions.rememberCarPlayAccess
import com.abid.handlebarlink.ui.permissions.rememberLocationAccess
import com.abid.handlebarlink.ui.permissions.rememberNearbyAccess
import com.abid.handlebarlink.ui.permissions.rememberNotificationAccess
import com.abid.handlebarlink.ui.permissions.rememberOverlayAccess
import com.abid.handlebarlink.ui.settings.ModeScreen
import com.abid.handlebarlink.ui.settings.PermissionsScreen
import com.abid.handlebarlink.ui.settings.SettingsAccess
import com.abid.handlebarlink.ui.settings.SettingsActions
import com.abid.handlebarlink.ui.settings.SettingsScreen
import com.abid.handlebarlink.ui.settings.SettingsViewModel
import com.abid.handlebarlink.ui.settings.ThemeScreen
import com.abid.handlebarlink.ui.setup.SetupRoute

/**
 * The app's frame: the background, then first-time setup until a bike is saved (docs/PLAN.md "First-time
 * setup"), and the tabs after that. While the setting loads (a few milliseconds) only the background shows. The
 * window's [WindowLayout] (column upright, panes on its side) is decided here once for every page.
 */
@Composable
fun AppRoot() {
    val main: MainViewModel = hiltViewModel()
    val bikeChosen by main.bikeChosen.collectAsStateWithLifecycle()
    val skipped by main.setupSkipped.collectAsStateWithLifecycle()
    // Skipping setup (temporary) shows the tabs without a bike; the ride still needs one.
    val showTabs = if (skipped) true else bikeChosen
    CompositionLocalProvider(LocalWindowLayout provides currentWindowLayout()) {
        Frame(showTabs, bikeChosen == true, main)
    }
}

@Composable
private fun Frame(
    showTabs: Boolean?,
    bikeChosen: Boolean,
    main: MainViewModel,
) {
    HlBackground(Modifier.fillMaxSize()) {
        Crossfade(showTabs, animationSpec = HlTheme.motion.fade(), label = "setup or tabs") { tabs ->
            when (tabs) {
                null -> {}

                false -> {
                    SetupRoute()
                }

                true -> {
                    RideAutoStart(main, bikeChosen)
                    Tabs(main)
                }
            }
        }
    }
}

/**
 * The tabs: the pages (one Navigation 3 back stack per tab) and the floating tab bar, shown on the tabs'
 * pages and hidden on full pages such as Settings. A handlebar press's page ([MainViewModel.screens]: our CarPlay
 * display, or switching to and from it) opens here.
 */
@Composable
private fun BoxScope.Tabs(main: MainViewModel) {
    val state = rememberNavigationState(TOP_LEVEL_DESTINATIONS)
    val navigator = remember(state) { Navigator(state) }
    LaunchedEffect(navigator) {
        main.screens.collect { request ->
            when (request) {
                ScreenRequest.OPEN_CARPLAY -> navigator.openCarPlayDisplay()
                ScreenRequest.SWITCH -> navigator.switchScreen()
            }
        }
    }
    val spacing = HlTheme.spacing
    val panes = LocalWindowLayout.current == WindowLayout.PANES
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val side = WindowInsets.pageSides.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr)
    val currentBarSpace =
        if (panes) {
            // On its side, room for the rail on the end edge and the navigation bar below. Pages pad both sides by
            // the mirrored side (WindowInsets.pageTop) and set their content 16 dp in, so the content's margin
            // to the screen's start, its gap to the rail and the rail's margin to the end are one width:
            // side + 16 dp (owner, 2026-11-20: "the right side padding is totally wrong").
            PaddingValues(end = railSpace(side, spacing.s16, HlTheme.sizes.tabRail), bottom = navigationBar)
        } else {
            // Pages leave room for the floating bar: its height, its 20 dp gap and the navigation bar.
            PaddingValues(bottom = HlTheme.sizes.tabBar + spacing.s20 * 2 + navigationBar)
        }
    // One object for every page, always reading the current room (LivePadding: pages keep what they were given).
    val barSpaceState = rememberUpdatedState(currentBarSpace)
    val barSpace = remember(barSpaceState) { LivePadding(barSpaceState) }
    val motion = HlTheme.motion
    val entries =
        entryProvider {
            entry<HomeRoute> {
                val viewModel: HomeViewModel = hiltViewModel()
                val home by viewModel.uiState.collectAsStateWithLifecycle()
                HomeScreen(
                    home,
                    onSettings = { navigator.navigate(SettingsRoute) },
                    contentPadding = barSpace,
                    onAddDevice = { navigator.navigate(AddDeviceRoute(ADD_DEVICE)) },
                    onDevice = { navigator.navigate(IntercomRoute) },
                    onHandlebar = { navigator.navigate(HandlebarRoute) },
                )
            }
            entry<RidesRoute> { LaterScreen(HlIcons.Rides, R.string.tab_rides, R.string.later_rides, barSpace) }
            carPlayEntries(navigator, barSpace)
            entry<GarageRoute> { LaterScreen(HlIcons.Garage, R.string.tab_garage, R.string.later_garage, barSpace) }
            intercomEntries(navigator, barSpace)
            settingsEntries(navigator)
            deviceEntries(navigator, motion)
            handlebarEntries(navigator, motion)
        }
    // The pages are the tab bar's backdrop: recorded with their ground, frosted under the bar.
    val barBackdrop = rememberBackdrop()
    HlBackground(Modifier.fillMaxSize().backdropSource(barBackdrop)) {
        // Shared bounds between pages (Add device grows out of its tile); the docs' setup for Navigation 3.
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                Pages(state, navigator, entries)
            }
        }
    }
    val onTabPage = state.currentPage in TOP_LEVEL_DESTINATIONS.map { it.route }
    // On its side the tab pages' second pane scrolls behind the status bar; keep the clock readable (full pages
    // such as Settings draw their own).
    if (panes && onTabPage) HlStatusBarProtection(Modifier.align(Alignment.TopCenter))
    if (panes) FloatingTabRail(state, navigator, barBackdrop) else FloatingTabBar(state, navigator, barBackdrop)
}

@Composable
private fun Pages(
    state: NavigationState,
    navigator: Navigator,
    entries: (NavKey) -> NavEntry<NavKey>,
) {
    val motion = HlTheme.motion
    NavDisplay(
        entries = state.toDecoratedEntries(entries),
        onBack = navigator::goBack,
        // Sheets ("What is it?", "Calls") and dialogs ("Remove …?"): overlays over the page under them; the
        // sheet strategy first, as the recipe asks of overlay strategies.
        sceneStrategies = listOf(SheetSceneStrategy(), DialogSceneStrategy()),
        transitionSpec = { pageTransition(state.lastMove, motion) },
        popTransitionSpec = { pageTransition(state.lastMove, motion) },
        // The back gesture previews what Back will do, following the finger (Android 15+ by default).
        predictivePopTransitionSpec = { pageTransition(navigator.backMove(), motion) },
    )
}

/** The tab bar floating over the pages; it slides away on full pages (Settings) and back on tab pages. */
@Composable
private fun BoxScope.FloatingTabBar(
    state: NavigationState,
    navigator: Navigator,
    backdrop: Backdrop,
) {
    val spacing = HlTheme.spacing
    val motion = HlTheme.motion
    AnimatedVisibility(
        // A sheet or dialog covers the page; the bar stays with the page under it.
        visible = state.currentPage in TOP_LEVEL_DESTINATIONS.map { it.route },
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
            backdrop = backdrop,
        )
    }
}

/**
 * The tab rail floating on the end edge on its side (where CarPlay keeps its dock): as tall as the pages' content
 * between the system bars and as far from the edge as the pages' content is from the other one (the mirrored side,
 * then 16 dp); it slides away on full pages like the bar.
 */
@Composable
private fun BoxScope.FloatingTabRail(
    state: NavigationState,
    navigator: Navigator,
    backdrop: Backdrop,
) {
    val motion = HlTheme.motion
    AnimatedVisibility(
        visible = state.currentPage in TOP_LEVEL_DESTINATIONS.map { it.route },
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally(motion.layout(IntOffset.VisibilityThreshold)) { it } + fadeIn(motion.fade()),
        exit = slideOutHorizontally(motion.layout(IntOffset.VisibilityThreshold)) { it } + fadeOut(motion.fade()),
    ) {
        HlTabRail(
            tabs = TOP_LEVEL_DESTINATIONS.map { HlTab(it.icon, stringResource(it.label)) },
            selected = TOP_LEVEL_DESTINATIONS.indexOfFirst { it.route == state.topLevelRoute },
            onSelect = { navigator.navigate(TOP_LEVEL_DESTINATIONS[it].route) },
            // As tall as the pages' content (the system bars, then 8 dp, like Home's panes), on every page.
            modifier =
                Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                    .windowInsetsPadding(WindowInsets.pageSides)
                    .padding(end = HlTheme.spacing.s16, top = HlTheme.spacing.s8, bottom = HlTheme.spacing.s8),
            backdrop = backdrop,
        )
    }
}

/** Settings and its pages (Permissions, Theme, Mode), pushed over Home's back stack. */
private fun EntryProviderScope<NavKey>.settingsEntries(navigator: Navigator) {
    entry<SettingsRoute> {
        SettingsEntry(
            onTheme = { navigator.navigate(ThemeRoute) },
            onMode = { navigator.navigate(ModeRoute) },
            onPermissions = { navigator.navigate(PermissionsRoute) },
            onBack = navigator::goBack,
        )
    }
    entry<PermissionsRoute> {
        val access = rememberAllAccess()
        val allowAll = rememberAllowAll(access.inOrder)
        PermissionsScreen(
            access.statuses(),
            onAct = { access.inOrder[it].act() },
            onBack = navigator::goBack,
            onAllowAll = allowAll,
        )
    }
    entry<ThemeRoute> {
        val viewModel: SettingsViewModel = hiltViewModel()
        val settings by viewModel.uiState.collectAsStateWithLifecycle()
        ThemeScreen(settings.theme, viewModel::choose, onBack = navigator::goBack)
    }
    entry<ModeRoute> {
        val viewModel: SettingsViewModel = hiltViewModel()
        val settings by viewModel.uiState.collectAsStateWithLifecycle()
        ModeScreen(settings.theme, onBack = navigator::goBack)
    }
}

/** Settings with its view model and the three permissions (re-read on every resume). */
@Composable
private fun SettingsEntry(
    onTheme: () -> Unit,
    onMode: () -> Unit,
    onPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val access = rememberAllAccess()
    val nearby = access.nearby.status
    // The meters can only be listed with Nearby devices; read them again whenever that or the page resumes.
    LifecycleResumeEffect(nearby) {
        viewModel.refreshMeters()
        onPauseOrDispose {}
    }
    SettingsScreen(
        state,
        access.statuses(),
        SettingsActions(
            onTheme = onTheme,
            onMode = onMode,
            onPermissions = onPermissions,
            onBike = viewModel::choose,
            onSetup = viewModel::setUpAgain,
            onBack = onBack,
        ),
    )
}

/** The permissions Settings shows (each re-read on every resume), CarPlay's last. */
private class AllAccess(
    /** In Settings' order, the index [PermissionsScreen] acts on: SettingsAccess.all(). */
    val inOrder: List<AccessState>,
) {
    val nearby: AccessState get() = inOrder[NEARBY]

    fun statuses() =
        SettingsAccess(
            inOrder[NEARBY].status,
            inOrder[NOTIFICATIONS].status,
            inOrder[LOCATION].status,
            inOrder[OVERLAY].status,
            inOrder[BRIGHTNESS].status,
            inOrder[CARPLAY].status,
        )

    /** Positions in [inOrder]. */
    private companion object {
        const val NEARBY = 0
        const val NOTIFICATIONS = 1
        const val LOCATION = 2
        const val OVERLAY = 3
        const val BRIGHTNESS = 4
        const val CARPLAY = 5
    }
}

@Composable
private fun rememberAllAccess() =
    AllAccess(
        listOf(
            rememberNearbyAccess(),
            rememberNotificationAccess(),
            rememberLocationAccess(),
            rememberOverlayAccess(),
            rememberBrightnessAccess(),
            rememberCarPlayAccess(),
        ),
    )

/**
 * Starts the ride while the app is on screen (a foreground service may only start then; [startsRideOnResume]): for
 * the bike once one is set up ([bikeChosen]) and Nearby devices is allowed, and for CarPlay once an iPhone is paired
 * and CarPlay's permissions are allowed, bike or not; a running ride ignores the second start. Each resume also
 * re-reads the permissions (Android sends no broadcast when they change). Waking on ignition, with the app closed, is
 * Android's companion-device presence (autostart/).
 */
@Composable
private fun RideAutoStart(
    viewModel: MainViewModel,
    bikeChosen: Boolean,
) {
    val context = LocalContext.current
    val nearby = rememberNearbyAccess()
    val carPlay = rememberCarPlayAccess()
    val carPlayWantsRide by viewModel.carPlayWantsRide.collectAsStateWithLifecycle()
    LifecycleResumeEffect(nearby.status, carPlay.status, carPlayWantsRide, bikeChosen) {
        viewModel.onResumed()
        val start =
            startsRideOnResume(
                bikeChosen = bikeChosen,
                nearbyGranted = nearby.status == Access.GRANTED,
                carPlayWantsRide = carPlayWantsRide,
                carPlayGranted = carPlay.status == Access.GRANTED,
            )
        if (start) RideService.start(context)
        onPauseOrDispose {}
    }
}

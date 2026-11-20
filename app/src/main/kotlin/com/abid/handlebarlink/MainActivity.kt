package com.abid.handlebarlink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.abid.handlebarlink.controls.EXTRA_SCREEN
import com.abid.handlebarlink.controls.screenRequest
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.ride.EXTRA_RIDE
import com.abid.handlebarlink.ride.RideService
import com.abid.handlebarlink.ui.AppRoot
import com.abid.handlebarlink.ui.BikeRender
import com.abid.handlebarlink.ui.LocalBikeRender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The single activity: the rider's theme around [AppRoot]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** See [skipSetupIfAsked]. */
    fun skipSetupNow() = viewModel.skipSetup()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The new intent is the activity's from now on (android-intent-security: setIntent before acting on it).
        setIntent(intent)
        showForRideIfAsked(intent)
        handleOnce(intent)
    }

    /** What a start asks for once (not again when the activity is only re-created): the same for every start. */
    private fun handleOnce(intent: Intent?) {
        if (BuildConfig.DEBUG) {
            skipSetupIfAsked(intent)
            startRideIfAsked(intent)
        }
        showScreenIfAsked(intent)
    }

    /** A handlebar press asked for our CarPlay display, or to switch to or from it (controls/ScreenSwitcher). */
    private fun showScreenIfAsked(intent: Intent?) {
        screenRequest(intent?.getStringExtra(EXTRA_SCREEN))?.let(viewModel::show)
    }

    /**
     * Started for a ride (the bike appeared, RideStarter.showOnScreen): the phone is the bike's display, so the
     * app turns the screen on and shows over the lock screen; the rider unlocks only to leave it.
     */
    private fun showForRideIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RIDE, false) == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    @Inject lateinit var bikeRender: BikeRender

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge without SystemBarStyle (deprecated in activity 1.14): bar icons follow our theme.
        WindowCompat.enableEdgeToEdge(window)
        super.onCreate(savedInstanceState)
        // The window flags belong to this instance, so again after a re-creation (a turn); the rest only once.
        showForRideIfAsked(intent)
        if (savedInstanceState == null) handleOnce(intent)
        // Decoded in the background while the first frames draw, then shared by every screen.
        lifecycleScope.launch { bikeRender.load() }
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalBikeRender provides bikeRender) {
                AppTheme(theme) { AppRoot() }
            }
        }
    }
}

/**
 * Temporary (owner, 2026-11-17), debug builds only (the launcher activity is exported, so any app could send it):
 * `adb shell am start -n com.abid.handlebarlink/.MainActivity --ez skip_setup true` opens the tabs without a bike,
 * to test the app away from it. Nothing is saved.
 */
private fun MainActivity.skipSetupIfAsked(intent: Intent?) {
    if (intent?.getBooleanExtra(EXTRA_SKIP_SETUP, false) == true) skipSetupNow()
}

private const val EXTRA_SKIP_SETUP = "skip_setup"

/**
 * Temporary, for testing CarPlay away from the bike (owner, 2026-11-19), debug builds only: `--ez start_ride true` on
 * the same command starts the ride service without a saved bike. The bike link finds nothing to do; CarPlay keeps the
 * ride alive through its own keepAlive. Started from the visible activity, so the foreground service start is allowed.
 * (With an iPhone paired the app now starts the ride by itself when it comes to the front: RideAutoStart.)
 */
private fun MainActivity.startRideIfAsked(intent: Intent?) {
    if (intent?.getBooleanExtra(EXTRA_START_RIDE, false) == true) RideService.start(this)
}

private const val EXTRA_START_RIDE = "start_ride"

/** Applies the rider's theme and matches the system bar icons to it (edge-to-edge skill). */
@Composable
private fun AppTheme(
    theme: AppTheme,
    content: @Composable () -> Unit,
) {
    val variant =
        when (theme) {
            AppTheme.NIGHT -> HlThemeVariant.NIGHT
            AppTheme.BENTO -> HlThemeVariant.BENTO
        }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as ComponentActivity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = variant == HlThemeVariant.BENTO
                isAppearanceLightNavigationBars = variant == HlThemeVariant.BENTO
            }
        }
    }
    HlTheme(variant, content)
}

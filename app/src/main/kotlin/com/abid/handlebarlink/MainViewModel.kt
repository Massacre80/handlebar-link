package com.abid.handlebarlink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.data.AppearanceSettings
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.ride.RideCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** App-wide UI state: the theme, and whether a bike is set up (setup or the tabs). */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        appearance: AppearanceSettings,
        bikes: BikeSettings,
        private val coordinator: RideCoordinator,
        private val carPlay: CarPlayCoordinator,
    ) : ViewModel() {
        /** The rider's theme (Night until loaded). */
        val theme: StateFlow<AppTheme> =
            appearance.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppTheme.NIGHT)

        /** Whether a bike is chosen: null while the setting loads, false shows setup, true the tabs. */
        val bikeChosen: StateFlow<Boolean?> =
            bikes.bike
                .map { it != null }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val skipped = MutableStateFlow(false)

        /**
         * Setup skipped for this process (temporary, owner 2026-11-17: test the app without a bike). Nothing
         * is saved; the next start shows setup again.
         */
        val setupSkipped: StateFlow<Boolean> = skipped.asStateFlow()

        /** Shows the tabs without a bike until the app restarts. */
        fun skipSetup() {
            skipped.value = true
        }

        /** The app came to the foreground: the Bluetooth and CarPlay permissions may have changed meanwhile. */
        fun onResumed() {
            coordinator.recheckPermission()
            carPlay.recheckPermission()
        }

        private companion object {
            /** Keeps the flow across configuration changes (Android architecture recommendations). */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

package com.abid.handlebarlink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.IphonePairing
import com.abid.handlebarlink.controls.ScreenRequest
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.data.AppearanceSettings
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.ride.RideCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-wide UI state: the theme, whether a bike is set up (setup or the tabs), whether CarPlay has an iPhone to start
 * a ride for, and the pages a handlebar press asks for ([ScreenRequest]).
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        appearance: AppearanceSettings,
        bikes: BikeSettings,
        pairing: IphonePairing,
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

        /**
         * Whether CarPlay wants a ride started from the app on screen: an iPhone is paired and the rider has not
         * stopped CarPlay (a ride that would end at once for lack of a reason to stay must not be started on every
         * resume). False until known.
         */
        val carPlayWantsRide: StateFlow<Boolean> =
            combine(pairing.iphones(), carPlay.stoppedByRider) { iphones, stopped ->
                !iphones.isNullOrEmpty() &&
                    !stopped
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        private val skipped = MutableStateFlow(false)

        /**
         * Setup skipped for this process (temporary, owner 2026-11-17: test the app without a bike; debug builds
         * only). Nothing is saved; the next start shows setup again.
         */
        val setupSkipped: StateFlow<Boolean> = skipped.asStateFlow()

        private val screenRequests = Channel<ScreenRequest>(Channel.CONFLATED)

        /** The pages a handlebar press asked for, each once; the tabs' navigation opens them. */
        val screens: Flow<ScreenRequest> = screenRequests.receiveAsFlow()

        /** Shows the tabs without a bike until the app restarts. */
        fun skipSetup() {
            skipped.value = true
        }

        /** A handlebar press asked for [request] (MainActivity's extra). */
        fun show(request: ScreenRequest) {
            screenRequests.trySend(request)
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

/**
 * Whether the app on screen starts the ride (RideAutoStart, on every resume; a foreground service may start only
 * while the app is visible): for the bike once it is set up ([bikeChosen]) and Nearby devices is allowed
 * ([nearbyGranted]); and for CarPlay, with or without a bike (it replaced CarLink: no switch), when it has an iPhone
 * and was not stopped by the rider ([carPlayWantsRide]) and all of CarPlay's permissions are allowed
 * ([carPlayGranted]: the ride service's `connectedDevice` start needs Bluetooth, which they include). A running ride
 * ignores the second start.
 */
internal fun startsRideOnResume(
    bikeChosen: Boolean,
    nearbyGranted: Boolean,
    carPlayWantsRide: Boolean,
    carPlayGranted: Boolean,
): Boolean = (bikeChosen && nearbyGranted) || (carPlayWantsRide && carPlayGranted)

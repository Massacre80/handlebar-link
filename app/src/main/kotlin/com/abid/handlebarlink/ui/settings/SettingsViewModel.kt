package com.abid.handlebarlink.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlaySettings
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.data.AppearanceSettings
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.PairedMeters
import com.abid.handlebarlink.data.SelectedBike
import com.abid.handlebarlink.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Settings' state: the look, the chosen bike and the paired meters to choose from. */
data class SettingsUiState(
    /** The chosen look. */
    val theme: AppTheme = AppTheme.NIGHT,
    /** The chosen bike, if any. */
    val bike: SelectedBike? = null,
    /** Paired Honda meters; null until read or while Nearby devices is not allowed. */
    val meters: List<SelectedBike>? = null,
    /** The CarPlay beta switch: its permissions are listed only while it is on. */
    val carPlay: Boolean = false,
)

/** Settings: the look, the bike, and refreshing the paired meters. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val appearance: AppearanceSettings,
        private val bikes: BikeSettings,
        private val paired: PairedMeters,
        carPlay: CarPlaySettings,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private val meters = MutableStateFlow<List<SelectedBike>?>(null)

        /** Settings' state. */
        val uiState: StateFlow<SettingsUiState> =
            combine(appearance.theme, bikes.bike, meters, carPlay.enabled, ::SettingsUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

        /** Stores [theme]; the whole app switches at once. */
        fun choose(theme: AppTheme) {
            viewModelScope.launch { appearance.setTheme(theme) }
        }

        /** Rides with [bike] from now on. */
        fun choose(bike: SelectedBike) {
            viewModelScope.launch { bikes.select(bike) }
        }

        /** "Set up a different bike": forgets this one, so the app shows setup again. */
        fun setUpAgain() {
            viewModelScope.launch { bikes.forget() }
        }

        /** Reads the paired meters again (after Nearby devices was allowed or a meter was paired). */
        fun refreshMeters() {
            viewModelScope.launch { meters.value = withContext(io) { paired.list() } }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

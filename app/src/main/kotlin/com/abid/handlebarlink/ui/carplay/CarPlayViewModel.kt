package com.abid.handlebarlink.ui.carplay

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.CarPlaySettings
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The CarPlay page: the beta switch and CarPlay's status line. */
@Immutable
data class CarPlayUiState(
    /** The beta switch. */
    val enabled: Boolean = false,
    /** The status line. */
    val line: CarPlayLine = CarPlayLine(R.string.carplay_interim, tone = SignalTone.IDLE),
)

/** CarPlay's status line for Home and the CarPlay page, counting down on the app's clock (carPlayLines). */
class CarPlayStatusLines
    @Inject
    constructor(
        coordinator: CarPlayCoordinator,
        time: TimeProvider,
    ) {
        /** The current line. */
        val lines: Flow<CarPlayLine> = carPlayLines(coordinator.status, time)
    }

/** The CarPlay page: reads and turns the beta switch, and shows the receiver's status. */
@HiltViewModel
class CarPlayViewModel
    @Inject
    constructor(
        private val coordinator: CarPlayCoordinator,
        private val settings: CarPlaySettings,
        lines: CarPlayStatusLines,
    ) : ViewModel() {
        /** The page's state; off until loaded. */
        val uiState: StateFlow<CarPlayUiState> =
            combine(settings.enabled, lines.lines, ::CarPlayUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CarPlayUiState())

        /** Turns our own receiver on or off; a running ride follows at once (CarPlayCoordinator). */
        fun setEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setEnabled(enabled) }
        }

        /** A permission answer came back: CarPlay reads the permissions again. */
        fun recheckPermission() {
            coordinator.recheckPermission()
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

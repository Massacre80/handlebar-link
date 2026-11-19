package com.abid.handlebarlink.ui.carplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.CarPlaySettings
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.IphonePairing
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.core.common.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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

/**
 * The CarPlay tab (Paper "CarPlay — flow v2", Tab 1–4): the hero for CarPlay's state, the iPhone, Link and beta switch
 * rows; Cancel and Disconnect stop CarPlay ([CarPlayCoordinator.hold]) until Start CarPlay.
 */
@HiltViewModel
class CarPlayViewModel
    @Inject
    constructor(
        private val coordinator: CarPlayCoordinator,
        private val settings: CarPlaySettings,
        pairing: IphonePairing,
        lines: CarPlayStatusLines,
    ) : ViewModel() {
        /** The page's state; off until loaded. */
        val uiState: StateFlow<CarPlayUiState> =
            combine(
                settings.enabled,
                coordinator.status,
                pairing.iphones(),
                pairing.chosen(),
                lines.lines,
                ::carPlayTab,
            ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CarPlayUiState())

        /** Turns our own receiver on or off; a running ride follows at once (CarPlayCoordinator). */
        fun setEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setEnabled(enabled) }
        }

        /** Start CarPlay: the switch on (if it was off) and a stop lifted; the caller starts the ride service. */
        fun start() {
            coordinator.resume()
            viewModelScope.launch { settings.setEnabled(true) }
        }

        /** Cancel (while connecting) or Disconnect (while connected): CarPlay stops until started again. */
        fun stop() {
            coordinator.hold()
        }

        /** A permission answer came back: CarPlay reads the permissions again. */
        fun recheckPermission() {
            coordinator.recheckPermission()
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** Whether [this] is our receiver connected to the iPhone. */
internal fun CarPlayStatus.isConnected(): Boolean = this is CarPlayStatus.Engine && state is CarPlayState.Connected

package com.abid.handlebarlink.ui.carplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.CarPlaySettings
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.CarPlayVideoStatsSource
import com.abid.handlebarlink.carplay.IphonePairing
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.setup.SetupConditions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * The CarPlay tab (Paper "CarPlay — flow v2", Tab 1–4, without the switch row): the hero for CarPlay's state, the
 * iPhone and Link rows, then the CarPlay screen row; Cancel and Disconnect stop CarPlay ([CarPlayCoordinator.hold])
 * until Start CarPlay. Whether Bluetooth is on is read only when the engine reports it unavailable (the hero's fix).
 */
@HiltViewModel
class CarPlayViewModel
    @Inject
    constructor(
        private val coordinator: CarPlayCoordinator,
        private val settings: CarPlaySettings,
        pairing: IphonePairing,
        lines: CarPlayStatusLines,
        video: CarPlayVideoStatsSource,
        private val conditions: SetupConditions,
    ) : ViewModel() {
        /** The page's state; Ready until loaded. */
        val uiState: StateFlow<CarPlayUiState> =
            combine(
                coordinator.status,
                pairing.iphones(),
                pairing.chosen(),
                combine(lines.lines, video.stats(), settings.orientation, ::Triple),
            ) { status, iphones, chosen, (line, stats, orientation) ->
                carPlayTab(status, iphones, chosen, line, stats, orientation, bluetoothOn(status))
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CarPlayUiState())

        /** Bluetooth's state, read only when the engine says it is unavailable (a Binder call, not on every update). */
        private fun bluetoothOn(status: CarPlayStatus): Boolean {
            val state = (status as? CarPlayStatus.Engine)?.state
            val unavailable = state is CarPlayState.Recovering && state.error is CarPlayError.BluetoothUnavailable
            return !unavailable || conditions.bluetoothOn()
        }

        /**
         * The rider chose [orientation] (the CarPlay screen sheet); returns once stored (the sheet then closes). A
         * running CarPlay reconnects with it.
         */
        suspend fun chooseOrientation(orientation: CarPlayOrientation) {
            settings.setOrientation(orientation)
        }

        /** Start CarPlay: a stop lifted, or a failed engine started again; the caller starts the ride service. */
        fun start() {
            coordinator.start()
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

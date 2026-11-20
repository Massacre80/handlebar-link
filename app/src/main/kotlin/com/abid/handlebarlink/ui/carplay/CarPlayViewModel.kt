package com.abid.handlebarlink.ui.carplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.CarPlaySettings
import com.abid.handlebarlink.carplay.CarPlayStatus
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
import java.time.Instant
import javax.inject.Inject

/** CarPlay's status line for Home and the CarPlay page, counting down on the app's clock (carPlayLines). */
class CarPlayStatusLines
    @Inject
    constructor(
        coordinator: CarPlayCoordinator,
        private val time: TimeProvider,
    ) {
        /** CarPlay's status as the screens show it (steady, [CarPlayCoordinator.status]). */
        val status: Flow<CarPlayStatus> = coordinator.status

        /** The current line. */
        val lines: Flow<CarPlayLine> = carPlayLines(status, time)

        /** Now on the app's clock (how much of a retry's pause is left, with each line). */
        fun now(): Instant = time.clock.instant()
    }

/**
 * The BikePlay tab (Paper "BikePlay — flow", Tab 1–9 and 1L–9L): the meter with BikePlay's state, its buttons, the
 * iPhone and Link rows, then the BikePlay screen row; Try now cuts a retry's pause short; Cancel and Disconnect stop
 * CarPlay ([CarPlayCoordinator.hold]) until Start CarPlay. Whether Bluetooth is on is read only when the engine reports
 * it unavailable (the hero's fix).
 */
@HiltViewModel
class CarPlayViewModel
    @Inject
    constructor(
        private val coordinator: CarPlayCoordinator,
        private val settings: CarPlaySettings,
        pairing: IphonePairing,
        private val lines: CarPlayStatusLines,
        quality: SteadyLinkQuality,
        private val conditions: SetupConditions,
    ) : ViewModel(),
        BikePlayCommands {
        /** The page's state; Ready until loaded. */
        val uiState: StateFlow<CarPlayUiState> =
            combine(
                coordinator.status,
                pairing.iphones(),
                pairing.chosen(),
                combine(lines.lines, quality.quality, settings.orientation, ::Triple),
            ) { status, iphones, chosen, (line, quality, orientation) ->
                // The Link row's quality comes steady from its own flow, not from each window ([steadyQuality]).
                carPlayTab(
                    status,
                    iphones,
                    chosen,
                    line,
                    null,
                    orientation,
                    bluetoothOn(status),
                    quality = quality,
                    now = lines.now(),
                )
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

        override fun start() {
            coordinator.start()
        }

        override fun stop() {
            coordinator.hold()
        }

        override fun recheckPermission() {
            coordinator.recheckPermission()
        }

        override fun retryNow() {
            coordinator.retryNow()
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** Whether [this] is our receiver connected to the iPhone. */
internal fun CarPlayStatus.isConnected(): Boolean = this is CarPlayStatus.Engine && state is CarPlayState.Connected

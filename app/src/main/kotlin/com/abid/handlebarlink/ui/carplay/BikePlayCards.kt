package com.abid.handlebarlink.ui.carplay

import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.IphonePairing
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.setup.SetupConditions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * Home's BikePlay card from the same sources as the BikePlay tab: the coordinator's status, the paired iPhones and the
 * chosen one, the status line (which ticks every second while a retry counts down, so the draining arc follows it), the
 * link's steady quality and, only when the engine says Bluetooth is unavailable, whether it is on. Its commands are the
 * tab's ([BikePlayCommands] on the coordinator).
 */
class BikePlayCards
    @Inject
    constructor(
        private val coordinator: CarPlayCoordinator,
        private val pairing: IphonePairing,
        private val lines: CarPlayStatusLines,
        private val quality: SteadyLinkQuality,
        private val conditions: SetupConditions,
        private val time: TimeProvider,
    ) : BikePlayCommands {
        /** The card now. */
        val cards: Flow<BikePlayCardState>
            get() =
                combine(
                    coordinator.status,
                    pairing.iphones(),
                    pairing.chosen(),
                    lines.lines,
                    quality.quality,
                ) { status, iphones, chosen, line, quality ->
                    bikePlayCard(status, iphones, chosen, line, quality, bluetoothOn(status), time.clock.instant())
                }.distinctUntilChanged()

        override fun start() {
            coordinator.start()
        }

        override fun stop() {
            coordinator.hold()
        }

        override fun retryNow() {
            coordinator.retryNow()
        }

        override fun recheckPermission() {
            coordinator.recheckPermission()
        }

        /** Bluetooth's state, read only when the engine says it is unavailable (a Binder call, as the tab). */
        private fun bluetoothOn(status: CarPlayStatus): Boolean {
            val state = (status as? CarPlayStatus.Engine)?.state
            val unavailable = state is CarPlayState.Recovering && state.error is CarPlayError.BluetoothUnavailable
            return !unavailable || conditions.bluetoothOn()
        }
    }

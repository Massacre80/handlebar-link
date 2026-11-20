package com.abid.handlebarlink.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.HandlebarMapping
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.devices.BluetoothDevices
import com.abid.handlebarlink.devices.DeviceSettings
import com.abid.handlebarlink.devices.primary
import com.abid.handlebarlink.ride.RideCoordinator
import com.abid.handlebarlink.ui.carplay.BikePlayCards
import com.abid.handlebarlink.ui.carplay.BikePlayCommands
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Home: the link's state for the pill, the devices, the handlebar and the BikePlay card (architecture review
 * M2.4–M3-9: one uiState, WhileSubscribed); the card's buttons act on BikePlay as the tab's do ([BikePlayCommands]).
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        coordinator: RideCoordinator,
        settings: BikeSettings,
        devices: DeviceSettings,
        bluetooth: BluetoothDevices,
        handlebar: HandlebarMapping,
        bikePlay: BikePlayCards,
    ) : ViewModel(),
        // The BikePlay card's buttons do what the BikePlay tab's do (rememberTabActions).
        BikePlayCommands by bikePlay {
        /** Home's state; "no bike" until loaded. */
        val uiState: StateFlow<HomeUiState> =
            combine(
                settings.bike,
                coordinator.shownState,
                combine(devices.devices, devices.primary, ::Pair),
                bluetooth.statuses(),
                combine(handlebar.current, bikePlay.cards, ::Pair),
            ) { bike, state, (saved, chosen), statuses, (mapping, card) ->
                val device =
                    saved.primary(chosen)?.let {
                        val status = statuses[it.address]
                        HomeDevice(it.name, it.model, it.kind, status?.connected == true, status?.battery)
                    }
                HomeUiState(
                    linkStatus(bike != null, state),
                    device,
                    handlebar = mapping.getValue(CallSituation.RIDING),
                    bikePlay = card,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState(LinkStatus.NO_BIKE))

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

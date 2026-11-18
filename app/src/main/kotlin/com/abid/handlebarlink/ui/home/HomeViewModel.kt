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
import com.abid.handlebarlink.ui.carplay.CarPlayStatusLines
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Home: the link's state for the pill (architecture review M2.4–M3-9: one uiState, WhileSubscribed). */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        coordinator: RideCoordinator,
        settings: BikeSettings,
        devices: DeviceSettings,
        bluetooth: BluetoothDevices,
        handlebar: HandlebarMapping,
        carPlay: CarPlayStatusLines,
    ) : ViewModel() {
        /** Home's state; "no bike" until loaded. */
        @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
        val uiState: StateFlow<HomeUiState> =
            combine(
                settings.bike,
                coordinator.link.flatMapLatest { it?.state ?: flowOf(null) },
                combine(devices.devices, devices.primary, ::Pair),
                bluetooth.statuses(),
                combine(handlebar.current, carPlay.lines, ::Pair),
            ) { bike, state, (saved, chosen), statuses, (mapping, carPlayLine) ->
                val device =
                    saved.primary(chosen)?.let {
                        val status = statuses[it.address]
                        HomeDevice(it.name, it.model, it.kind, status?.connected == true, status?.battery)
                    }
                HomeUiState(
                    linkStatus(bike != null, state),
                    device,
                    handlebar = mapping.getValue(CallSituation.RIDING),
                    carPlay = carPlayLine,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState(LinkStatus.NO_BIKE))

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

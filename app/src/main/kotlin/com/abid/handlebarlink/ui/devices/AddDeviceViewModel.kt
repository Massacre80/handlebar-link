package com.abid.handlebarlink.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.devices.BluetoothDevices
import com.abid.handlebarlink.devices.DeviceSettings
import com.abid.handlebarlink.devices.PairedDevice
import com.abid.handlebarlink.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Add device: Android's paired devices with their live state. Android has no flow of the paired set, so the
 * page re-reads it when it comes back to the front ([refresh]: after Bluetooth settings, a permission or
 * turning Bluetooth on); connections update live.
 */
@HiltViewModel
class AddDeviceViewModel
    @Inject
    constructor(
        private val bluetooth: BluetoothDevices,
        devices: DeviceSettings,
        bikes: BikeSettings,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private val refreshes = MutableStateFlow(0)

        /** The page's state; "loading" until the first read. */
        @OptIn(ExperimentalCoroutinesApi::class) // mapLatest, flatMapLatest
        val uiState: StateFlow<AddDeviceUiState> =
            combine(
                refreshes.mapLatest { withContext(io) { read() } },
                // Restarted on refresh: live state needs the permission the rider may just have given.
                refreshes.flatMapLatest { bluetooth.statuses() },
                devices.devices,
                bikes.bike,
            ) { read, statuses, saved, bike ->
                when (read) {
                    Read.NotAllowed -> AddDeviceUiState.NotAllowed
                    Read.Off -> AddDeviceUiState.BluetoothOff
                    is Read.Paired -> deviceChoices(read.devices, statuses, saved, bike?.address)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AddDeviceUiState.Loading)

        /** Reads the paired devices again. */
        fun refresh() = refreshes.update { it + 1 }

        private fun read(): Read {
            val paired = bluetooth.paired() ?: return Read.NotAllowed
            return if (bluetooth.isOn()) Read.Paired(paired) else Read.Off
        }

        private sealed interface Read {
            data object NotAllowed : Read

            data object Off : Read

            data class Paired(
                val devices: List<PairedDevice>,
            ) : Read
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

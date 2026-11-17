package com.abid.handlebarlink.ui.intercom

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.devices.AudioRouting
import com.abid.handlebarlink.devices.BluetoothDevices
import com.abid.handlebarlink.devices.DeviceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Intercom page: the added devices, the rider's choices and what Android says about audio, live. */
@HiltViewModel
class IntercomViewModel
    @Inject
    constructor(
        private val devices: DeviceSettings,
        bluetooth: BluetoothDevices,
        private val routing: AudioRouting,
    ) : ViewModel() {
        /** The page's state. */
        val uiState: StateFlow<IntercomUiState> =
            combine(
                combine(devices.devices, combine(devices.primary, devices.calls, ::Choices), ::Pair),
                bluetooth.statuses(),
                combine(routing.mediaRoute(), routing.mediaKeys(), routing.mediaOutputs(), ::AudioNow),
            ) { (saved, chosen), statuses, audio ->
                intercomState(saved, chosen, statuses, audio, routing.vendorApp() != null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), IntercomUiState())

        /** Makes [address] the primary device. */
        fun choosePrimary(address: String) {
            viewModelScope.launch { devices.choosePrimary(address) }
        }

        /** Sends call audio to [route] (a device's address or PHONE); returns once stored. */
        suspend fun chooseCallsNow(route: String) = devices.chooseCalls(route)

        /** Removes [address] from the app (Android's pairing stays); returns once stored. */
        suspend fun removeNow(address: String) = devices.remove(address)

        /** Moves all media to the output [id]. */
        fun playOn(id: String) = routing.playOn(id)

        /** The intercom maker's app, to open. */
        fun vendorApp(): Intent? = routing.vendorApp()

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

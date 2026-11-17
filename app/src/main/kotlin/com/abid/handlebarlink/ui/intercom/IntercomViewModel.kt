package com.abid.handlebarlink.ui.intercom

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.devices.AudioRouting
import com.abid.handlebarlink.devices.BluetoothDevices
import com.abid.handlebarlink.devices.DeviceSettings
import com.abid.handlebarlink.devices.HeadsetLink
import com.abid.handlebarlink.devices.Reconnect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
        private val link: HeadsetLink,
    ) : ViewModel() {
        private val reconnecting = MutableStateFlow<Reconnecting>(Reconnecting.Idle)

        /** The page's state. */
        val uiState: StateFlow<IntercomUiState> =
            combine(
                combine(devices.devices, combine(devices.primary, devices.calls, ::Choices), ::Pair),
                bluetooth.statuses(),
                combine(routing.mediaRoute(), routing.mediaKeys(), routing.mediaOutputs(), ::AudioNow),
                reconnecting,
            ) { (saved, chosen), statuses, audio, reconnect ->
                // Connecting ends when Android reports the device connected.
                val shown =
                    if (reconnect is Reconnecting.Connecting && statuses[reconnect.address]?.connected == true) {
                        Reconnecting.Idle
                    } else {
                        reconnect
                    }
                intercomState(saved, chosen, statuses, audio, routing.vendorApp() != null).copy(reconnect = shown)
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

        /** Reconnects [address]: Android's companion link first if the app has none (a consent to show). */
        fun reconnect(address: String) {
            when (link.reconnect(address)) {
                Reconnect.STARTED -> {
                    reconnecting.value = Reconnecting.Connecting(address)
                    viewModelScope.launch {
                        delay(CONNECT_TIMEOUT_MS)
                        reconnecting.update { if (it == Reconnecting.Connecting(address)) Reconnecting.Failed else it }
                    }
                }

                Reconnect.NOT_LINKED -> {
                    viewModelScope.launch {
                        reconnecting.value =
                            link.request(address)?.let { Reconnecting.Linking(it, address) } ?: Reconnecting.Failed
                    }
                }

                Reconnect.NOT_SUPPORTED -> {
                    reconnecting.value = Reconnecting.Unsupported
                }

                Reconnect.FAILED -> {
                    reconnecting.value = Reconnecting.Failed
                }
            }
        }

        /** The consent was shown; on a link, reconnects at once. */
        fun linked(
            resultCode: Int,
            data: Intent?,
        ) {
            reconnecting.value = Reconnecting.Idle
            link.linkedFrom(resultCode, data)?.let(::reconnect)
        }

        /** The intercom maker's app, to open. */
        fun vendorApp(): Intent? = routing.vendorApp()

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L

            /** How long "Connecting…" shows before it counts as not connected. */
            const val CONNECT_TIMEOUT_MS = 20_000L
        }
    }

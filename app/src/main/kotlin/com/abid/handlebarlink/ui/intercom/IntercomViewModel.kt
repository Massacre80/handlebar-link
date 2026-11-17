package com.abid.handlebarlink.ui.intercom

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.devices.AssistantButton
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** The Intercom page: the added devices, the rider's choices and what Android says about audio, live. */
@HiltViewModel
class IntercomViewModel
    @Inject
    constructor(
        private val devices: DeviceSettings,
        private val bluetooth: BluetoothDevices,
        private val routing: AudioRouting,
        private val link: HeadsetLink,
        private val button: AssistantButton,
    ) : ViewModel() {
        private val handler = MutableStateFlow(button.handler())

        private val reconnecting = MutableStateFlow<Reconnecting>(Reconnecting.Idle)
        private val statuses get() = bluetooth.statuses()

        /** The page's state. */
        val uiState: StateFlow<IntercomUiState> =
            combine(
                combine(devices.devices, combine(devices.primary, devices.calls, ::Choices), ::Pair),
                bluetooth.statuses(),
                combine(routing.mediaRoute(), routing.mediaOutputs(), ::AudioNow),
                reconnecting,
                combine(button.action, handler, ::Pair),
            ) { (saved, chosen), statuses, audio, reconnect, (assistant, handler) ->
                intercomState(saved, chosen, statuses, audio, routing.vendorApp() != null)
                    .copy(reconnect = reconnect, assistant = assistant, handler = handler)
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
                        // "Connected" once Android reports it, for a moment, then the card's battery again.
                        val connected =
                            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                                statuses.first { it[address]?.connected == true }
                            } != null
                        reconnecting.value = if (connected) Reconnecting.Connected(address) else Reconnecting.Failed
                        if (connected) {
                            delay(CONNECTED_SHOWN_MS)
                            reconnecting.update { if (it == Reconnecting.Connected(address)) Reconnecting.Idle else it }
                        }
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

        /** Sets what the intercom's assistant button does; returns once stored. */
        suspend fun chooseAssistantNow(action: RiderAction) = button.choose(action)

        /** Reads again who gets the assistant button (the rider may have chosen "Always" meanwhile). */
        fun refreshHandler() {
            handler.value = button.handler()
        }

        /** The intercom maker's app, to open. */
        fun vendorApp(): Intent? = routing.vendorApp()

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L

            /** How long "Connected" shows before the card shows its battery again. */
            const val CONNECTED_SHOWN_MS = 1_500L

            /** How long "Connecting…" shows before it counts as not connected. */
            const val CONNECT_TIMEOUT_MS = 20_000L
        }
    }

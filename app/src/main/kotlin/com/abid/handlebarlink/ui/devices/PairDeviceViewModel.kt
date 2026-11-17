package com.abid.handlebarlink.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.devices.BluetoothPairing
import com.abid.handlebarlink.devices.NearbyDevice
import com.abid.handlebarlink.devices.PairState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Pair a new device: one search while the page is shown (it stops when the page goes, and when pairing
 * starts, as the docs ask), and one pairing at a time.
 */
@HiltViewModel
class PairDeviceViewModel
    @Inject
    constructor(
        private val pairing: BluetoothPairing,
    ) : ViewModel() {
        private val searches = MutableStateFlow(0)
        private val attempt = MutableStateFlow<PairAttempt?>(null)
        private var pairJob: Job? = null

        /** The page's state. */
        @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
        val uiState: StateFlow<PairDeviceUiState> =
            combine(searches.flatMapLatest { pairing.discover() }, attempt, ::pairDeviceState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PairDeviceUiState.Loading)

        /** Searches again (also after a permission, or Bluetooth turned on). */
        fun searchAgain() {
            if (attempt.value?.state == PairState.PAIRING) return
            searches.update { it + 1 }
        }

        /** Pairs with [device], unless a pairing is under way. */
        fun pair(device: NearbyDevice) {
            if (pairJob?.isActive == true) return
            pairJob =
                viewModelScope.launch {
                    pairing.pair(device.address).collect { attempt.value = PairAttempt(device, it) }
                }
        }

        /** The paired device was handed to "What is it?". */
        fun handedOver() {
            attempt.value = null
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

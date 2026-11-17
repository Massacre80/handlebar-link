package com.abid.handlebarlink.ui.devices

import com.abid.handlebarlink.devices.Discovery
import com.abid.handlebarlink.devices.NearbyDevice
import com.abid.handlebarlink.devices.PairState
import com.abid.handlebarlink.setup.isHondaMeter

/** Pair a new device. */
sealed interface PairDeviceUiState {
    /** Starting. */
    data object Loading : PairDeviceUiState

    /** Nearby devices is not allowed: no search or pairing. */
    data object NotAllowed : PairDeviceUiState

    /** Bluetooth is off. */
    data object BluetoothOff : PairDeviceUiState

    /** The search: audio devices first, the rest folded; and the pairing in progress, if any. */
    data class Nearby(
        val audio: List<NearbyDevice>,
        val other: List<NearbyDevice>,
        val searching: Boolean,
        /** The device being paired, failed or just paired, with where it stands. */
        val attempt: PairAttempt?,
    ) : PairDeviceUiState
}

/** One pairing: [device] and its [state]. */
data class PairAttempt(
    val device: NearbyDevice,
    val state: PairState,
)

/** What Pair a new device can do. */
data class PairDeviceActions(
    val onAllow: () -> Unit,
    val onTurnOn: () -> Unit,
    val onPair: (NearbyDevice) -> Unit,
    val onSearchAgain: () -> Unit,
    /** Android's Bluetooth settings, for a device that needs a typed PIN. */
    val onNotListed: () -> Unit,
    val onBack: () -> Unit,
)

/** The page from a search: Honda meters left out (the bike is not a device), audio first, then by name. */
fun pairDeviceState(
    discovery: Discovery,
    attempt: PairAttempt?,
): PairDeviceUiState =
    when (discovery) {
        Discovery.NotAllowed -> {
            PairDeviceUiState.NotAllowed
        }

        Discovery.Off -> {
            PairDeviceUiState.BluetoothOff
        }

        is Discovery.Found -> {
            val (audio, other) =
                discovery.devices
                    .filterNot { isHondaMeter(it.name) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    .partition { it.audio }
            PairDeviceUiState.Nearby(audio, other, discovery.searching, attempt)
        }
    }

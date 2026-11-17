package com.abid.handlebarlink.ui.devices

import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.devices.DeviceStatus
import com.abid.handlebarlink.devices.PairedDevice
import com.abid.handlebarlink.devices.SavedDevice
import com.abid.handlebarlink.setup.isHondaMeter

/** The Add device page. */
sealed interface AddDeviceUiState {
    /** Reading the paired devices. */
    data object Loading : AddDeviceUiState

    /** Nearby devices is not allowed: no list can be read. */
    data object NotAllowed : AddDeviceUiState

    /** Bluetooth is off: Android lists nothing. */
    data object BluetoothOff : AddDeviceUiState

    /** The paired devices: audio ones first, the rest folded away. */
    data class Ready(
        val audio: List<DeviceChoice>,
        val other: List<DeviceChoice>,
    ) : AddDeviceUiState
}

/** A paired device as the list shows it. */
data class DeviceChoice(
    val address: String,
    /** The rider's name for it once added, else [model]. */
    val name: String,
    /** Its own Bluetooth name, or the address when it has none. */
    val model: String,
    /** Connected to this phone now (Headset or A2DP). */
    val connected: Boolean,
    /** Already added in the app. */
    val added: Boolean,
    /** The saved kind once added, else what it looks like. */
    val kind: DeviceKind,
    val audio: Boolean,
)

/**
 * The list from Android's paired devices: the bike's meter left out (it is the bike, not a device), audio
 * devices first with the connected ones on top, then by name; everything else apart.
 */
fun deviceChoices(
    paired: List<PairedDevice>,
    statuses: Map<String, DeviceStatus>,
    saved: List<SavedDevice>,
    bikeAddress: String?,
): AddDeviceUiState.Ready {
    val savedBy = saved.associateBy { it.address }
    val choices =
        paired
            .filter { it.address != bikeAddress && it.name?.let(::isHondaMeter) != true }
            .map { device ->
                val connected = statuses[device.address]?.connected == true
                DeviceChoice(
                    address = device.address,
                    name = savedBy[device.address]?.name ?: device.name ?: device.address,
                    model = device.name ?: device.address,
                    connected = connected,
                    added = device.address in savedBy,
                    kind = savedBy[device.address]?.kind ?: device.suggested,
                    // A live Headset or A2DP connection proves audio even when the class says otherwise.
                    audio = device.audio || connected,
                )
            }
    val order = compareByDescending<DeviceChoice> { it.connected }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    val (audio, other) = choices.partition { it.audio }
    return AddDeviceUiState.Ready(audio.sortedWith(order), other.sortedWith(order))
}

/** What Add device can do; the page holds no logic of its own. */
data class AddDeviceActions(
    /** Asks for Nearby devices (or opens the app's settings once refused for good). */
    val onAllow: () -> Unit,
    /** Asks Android to turn Bluetooth on. */
    val onTurnOn: () -> Unit,
    /** Opens "What is it?" for a device. */
    val onPick: (DeviceChoice) -> Unit,
    /** Opens Android's Bluetooth settings to pair a new device. */
    val onPair: () -> Unit,
    val onBack: () -> Unit,
)

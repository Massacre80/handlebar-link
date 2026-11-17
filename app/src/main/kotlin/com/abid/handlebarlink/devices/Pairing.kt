package com.abid.handlebarlink.devices

import kotlinx.coroutines.flow.Flow

/** A device found nearby in pairing mode (not paired with this phone yet). */
data class NearbyDevice(
    val address: String,
    val name: String,
    /** An audio device by its Bluetooth class. */
    val audio: Boolean,
    /** What it looks like: the sheet's suggestion. */
    val suggested: DeviceKind,
)

/** A search for devices in pairing mode. */
sealed interface Discovery {
    /** Nearby devices is not allowed: searching and pairing need it. */
    data object NotAllowed : Discovery

    /** Bluetooth is off. */
    data object Off : Discovery

    /** What was found so far, named ones only; [searching] until Android's search ends (about 12 s). */
    data class Found(
        val devices: List<NearbyDevice>,
        val searching: Boolean,
    ) : Discovery
}

/** Where pairing with one device stands. */
enum class PairState {
    /** Asked; Android may show its own confirm or PIN prompt. */
    PAIRING,

    /** Paired with this phone. */
    PAIRED,

    /** Refused, timed out or not possible. */
    FAILED,
}

/** Pairing new devices inside the app (developer.android.com, "Find Bluetooth devices"). */
interface BluetoothPairing {
    /** Searches for devices in pairing mode while collected; the search stops when collection ends. */
    fun discover(): Flow<Discovery>

    /** Pairs with [address]: [PairState.PAIRING], then PAIRED or FAILED. Stops any search first. */
    fun pair(address: String): Flow<PairState>
}

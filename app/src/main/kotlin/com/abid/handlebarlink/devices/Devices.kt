package com.abid.handlebarlink.devices

import kotlinx.coroutines.flow.Flow

/** What an added device is (the "What is it?" sheet). */
enum class DeviceKind {
    /** A helmet intercom: calls, music, directions and rider-to-rider talk. */
    INTERCOM,

    /** Headset or earbuds: audio only. */
    HEADSET,

    /** Any other Bluetooth device. */
    OTHER,
}

/** A device the rider added: Android's pairing, plus the kind and the name shown on Home. */
data class SavedDevice(
    val address: String,
    val name: String,
    val kind: DeviceKind,
    /** The device's own Bluetooth name ("EJEAS V6 Pro+"), shown under the rider's [name]. */
    val model: String,
)

/** A device paired (bonded) with this phone, as the Add device list shows it. */
data class PairedDevice(
    val address: String,
    /** Its cleaned Bluetooth name, or null when it has none. */
    val name: String?,
    /** An audio device by its Bluetooth class (a hint, BluetoothClass docs) or a live audio connection. */
    val audio: Boolean,
    /** What it looks like: the sheet's suggestion. */
    val suggested: DeviceKind,
)

/** A device's live state; [battery] in percent, null until the device reports it (never shown as 0). */
data class DeviceStatus(
    val connected: Boolean,
    val battery: Int? = null,
    /** Connected for calls (the Headset profile). */
    val calls: Boolean = false,
    /** Connected for music (the A2DP profile). */
    val music: Boolean = false,
)

/** The devices the rider added, in the app's settings DataStore. */
interface DeviceSettings {
    /** In the order they were added. */
    val devices: Flow<List<SavedDevice>>

    /** Adds [device], or updates its kind and name when it is already added. */
    suspend fun save(device: SavedDevice)

    /** Removes the device at [address] from the app (Android's pairing stays). */
    suspend fun remove(address: String)

    /** The device the rider chose as primary (Home's tile, calls), if any. */
    val primary: Flow<String?>

    /** Makes [address] the primary device. */
    suspend fun choosePrimary(address: String)

    /** Where the rider chose call audio to go: a device's address or [PHONE]; null: the primary device. */
    val calls: Flow<String?>

    /** Sends call audio to [route]: a device's address or [PHONE]. */
    suspend fun chooseCalls(route: String)
}

/** The phone itself as the place for call audio (earpiece or speaker). */
const val PHONE: String = "phone"

/** Android's view of Bluetooth devices: what is paired, and what each is doing now. */
interface BluetoothDevices {
    /** Bluetooth is on. */
    fun isOn(): Boolean

    /**
     * The paired devices, or null when the Nearby devices permission is missing. A binder call: not on the
     * main thread.
     */
    fun paired(): List<PairedDevice>?

    /** Live connection and battery per address, while collected. */
    fun statuses(): Flow<Map<String, DeviceStatus>>
}

/**
 * The primary device (Home's tile, calls): the one the rider chose while it is still added, else the first
 * intercom, else the first added.
 */
fun List<SavedDevice>.primary(chosen: String?): SavedDevice? =
    firstOrNull { it.address == chosen } ?: firstOrNull { it.kind == DeviceKind.INTERCOM } ?: firstOrNull()

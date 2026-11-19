package com.abid.handlebarlink.carplay.engine.android.pairing

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A bonded device with its cached service records. */
internal data class RadioDevice(
    val address: String,
    val name: String?,
    val uuids: Set<UUID>,
)

/**
 * The Bluetooth calls pairing needs: the seam over BluetoothAdapter and its broadcasts, so [CarPlayPairing]'s rules
 * are tested on the JVM ([AndroidPairingRadio] in production). Calls are blocking Binder calls: never on the main
 * thread. Without BLUETOOTH_CONNECT every read answers as if there were nothing and every command does nothing.
 */
internal interface PairingRadio {
    /** BLUETOOTH_CONNECT is granted. */
    fun allowed(): Boolean

    /** Bluetooth is on. */
    fun isOn(): Boolean

    /** This phone's Bluetooth name, or null. */
    fun localName(): String?

    /** Sets this phone's Bluetooth name (BluetoothAdapter.setName); false when Android refused. */
    fun rename(name: String): Boolean

    /** The bonded devices. */
    fun bonded(): List<RadioDevice>

    /** Asks [address] for fresh service records; they arrive as [PairingEvent.Services]. */
    fun fetchServices(address: String)

    /** Bluetooth's broadcasts as events, for as long as the flow is collected. */
    fun events(): Flow<PairingEvent>
}

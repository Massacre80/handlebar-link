package com.abid.handlebarlink.bike.link.gatt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * One connected generation's GATT operations, as the meter session needs them
 * (docs/research/02-ble-connection.md §6.1). The Android implementation runs one operation at a time;
 * every operation either returns or throws [com.abid.handlebarlink.bike.link.LinkException]. Timeouts
 * are applied by the caller.
 */
internal interface GattLink {
    /** Notifications from the meter. Hot: subscribe before writing a request that expects a reply. */
    val notifications: Flow<GattNotification>

    /** The bond state with the meter; GATT work waits while it is [BondState.BONDING] (C6). */
    val bondState: StateFlow<BondState>

    /** Requests [mtu]; returns the negotiated MTU. */
    suspend fun requestMtu(mtu: Int): Int

    /** Discovers services; afterwards [hasCharacteristic] answers from the result. */
    suspend fun discoverServices()

    /** Whether the discovered table contains [characteristic] in [service]. */
    fun hasCharacteristic(
        service: UUID,
        characteristic: UUID,
    ): Boolean

    /** Reads a characteristic. */
    suspend fun read(
        service: UUID,
        characteristic: UUID,
    ): ByteArray

    /** Enables notifications (CCCD `01 00`) on a characteristic. */
    suspend fun enableNotifications(
        service: UUID,
        characteristic: UUID,
    )

    /**
     * Writes with the characteristic's default write type (write-without-response on the meter's
     * control points, SAB_PROTOCOL.md "Verified") and returns once the stack confirms it.
     */
    suspend fun write(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    )
}

/** A notification: the [characteristic] it came from and its [value]. */
internal class GattNotification(
    val characteristic: UUID,
    val value: ByteArray,
)

/** Bond state with the meter. */
internal enum class BondState { NONE, BONDING, BONDED }

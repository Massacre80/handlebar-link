package com.abid.handlebarlink.bike.link.spi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * One connected generation's GATT operations, as the meter session needs them
 * (docs/research/02-ble-connection.md §6.1). Implemented by the platform adapter (`:bike:link-android`)
 * and by test fakes. One operation runs at a time; each returns or throws
 * [com.abid.handlebarlink.bike.link.LinkException]. The caller applies timeouts.
 */
public interface GattLink {
    /** Notifications from the meter. Hot: subscribe before writing a request that expects a reply. */
    public val notifications: Flow<GattNotification>

    /** The bond state with the meter; GATT work waits while it is [BondState.BONDING] (C6). */
    public val bondState: StateFlow<BondState>

    /** Requests [mtu]; returns the negotiated MTU. */
    public suspend fun requestMtu(mtu: Int): Int

    /** Discovers services; afterwards [hasCharacteristic] answers from the result. */
    public suspend fun discoverServices()

    /** Whether the discovered table contains [characteristic] in [service]. */
    public fun hasCharacteristic(
        service: UUID,
        characteristic: UUID,
    ): Boolean

    /**
     * The discovered GATT table: every service, characteristic (with its properties) and descriptor, in the
     * order the stack lists them. Empty before [discoverServices]. Read only by the GATT probe.
     */
    public fun services(): List<GattServiceInfo>

    /** Reads a descriptor of a characteristic (the GATT probe only). */
    public suspend fun readDescriptor(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
    ): ByteArray

    /** Reads a characteristic. */
    public suspend fun read(
        service: UUID,
        characteristic: UUID,
    ): ByteArray

    /** Enables notifications (CCCD `01 00`) on a characteristic. */
    public suspend fun enableNotifications(
        service: UUID,
        characteristic: UUID,
    )

    /**
     * Writes with the characteristic's default write type (write-without-response on the meter's
     * control points, SAB_PROTOCOL.md "Verified") and returns once the stack confirms it.
     */
    public suspend fun write(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    )
}

/** A discovered service and its characteristics. */
public data class GattServiceInfo(
    /** The service. */
    val uuid: UUID,
    /** Its characteristics, as discovered. */
    val characteristics: List<GattCharacteristicInfo>,
)

/** A discovered characteristic. */
public data class GattCharacteristicInfo(
    /** The characteristic. */
    val uuid: UUID,
    /** Its properties bit field (Bluetooth Core, Vol 3 Part G 3.3.1.1: 0x02 read, 0x10 notify, …). */
    val properties: Int,
    /** Its descriptors. */
    val descriptors: List<UUID>,
)

/** A notification from the meter. */
public class GattNotification(
    /** The characteristic it came from. */
    public val characteristic: UUID,
    /** Its value. */
    public val value: ByteArray,
)

/** Bond state with the meter. */
public enum class BondState {
    /** Not bonded. */
    NONE,

    /** Bonding in progress. */
    BONDING,

    /** Bonded. */
    BONDED,
}

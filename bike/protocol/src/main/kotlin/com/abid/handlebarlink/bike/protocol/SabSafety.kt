package com.abid.handlebarlink.bike.protocol

import java.util.UUID

/**
 * The hard safety rules (docs/protocol/SAB_PROTOCOL.md §8): the meter's firmware-update service is
 * never written or subscribed to (and only its version is read), and data command 0x0B
 * (FIRMWARE_UPDATE) is never sent.
 *
 * The Bluetooth transport must check every write, subscription and probe read here before it goes out.
 */
public object SabSafety {
    /** Whether writing [value] to [characteristic] of [service] is allowed. */
    public fun isWriteAllowed(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    ): Boolean =
        when {
            service == SabUuids.DFU_SERVICE -> false

            characteristic == SabUuids.DATA_CONTROL_POINT &&
                value.firstOrNull() == DataCommandId.FIRMWARE_UPDATE.code.toByte() -> false

            else -> true
        }

    /**
     * Whether reading [characteristic] of [service] (and its descriptors) is allowed: nothing in the DFU
     * service except its firmware version, which RoadSync reads on every connect (§2.1).
     */
    public fun isReadAllowed(
        service: UUID,
        characteristic: UUID,
    ): Boolean = service != SabUuids.DFU_SERVICE || characteristic == SabUuids.DFU_FIRMWARE_VERSION

    /** Whether enabling notifications on a characteristic of [service] is allowed. */
    public fun isSubscribeAllowed(service: UUID): Boolean = service != SabUuids.DFU_SERVICE
}

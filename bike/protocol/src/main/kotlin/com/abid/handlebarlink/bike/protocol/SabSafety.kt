package com.abid.handlebarlink.bike.protocol

import java.util.UUID

/**
 * The hard safety rules (docs/protocol/SAB_PROTOCOL.md §8): the meter's firmware-update service is
 * never written or subscribed to, and data command 0x0B (FIRMWARE_UPDATE) is never sent.
 *
 * The Bluetooth transport must check every write and subscription here before it goes out.
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

    /** Whether enabling notifications on a characteristic of [service] is allowed. */
    public fun isSubscribeAllowed(service: UUID): Boolean = service != SabUuids.DFU_SERVICE
}

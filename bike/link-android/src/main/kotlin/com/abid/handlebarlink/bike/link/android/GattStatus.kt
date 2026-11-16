package com.abid.handlebarlink.bike.link.android

import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError

/** Turns GATT operation statuses into [LinkError]s (docs/research/02-ble-connection.md §2.6). */
internal object GattStatus {
    /** GATT_INSUFFICIENT_AUTHENTICATION. */
    const val INSUFFICIENT_AUTHENTICATION = 5

    /** GATT_INSUFFICIENT_ENCRYPTION. */
    const val INSUFFICIENT_ENCRYPTION = 15

    /** GATT_AUTH_FAIL (stack-internal 0x89). */
    const val AUTH_FAIL = 137

    /** Our marker for "the stack refused to start the operation". */
    const val NOT_STARTED = -1

    private val AUTHENTICATION = setOf(INSUFFICIENT_AUTHENTICATION, INSUFFICIENT_ENCRYPTION, AUTH_FAIL)

    /** The error for a failed [op] with [status]: authentication problems count towards a broken bond. */
    fun errorFor(
        op: GattOp,
        status: Int,
    ): LinkError =
        if (status in AUTHENTICATION) LinkError.AuthenticationFailed(status) else LinkError.GattFailed(op, status)
}

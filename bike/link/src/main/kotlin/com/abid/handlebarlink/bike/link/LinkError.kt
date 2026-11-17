package com.abid.handlebarlink.bike.link

import com.abid.handlebarlink.bike.protocol.AssignmentStatus
import com.abid.handlebarlink.bike.protocol.FunctionId

/** How the link gets out of an error (docs/research/01-architecture.md §4.2). */
public enum class Recovery {
    /** Retry by itself after a growing pause. */
    BACKOFF,

    /** Wait until Bluetooth is switched on. */
    WAIT_FOR_BLUETOOTH,

    /** Wait until the rider does something (grant a permission, pair the bike again). */
    WAIT_FOR_USER,
}

/** A GATT operation, for error reports. */
public enum class GattOp {
    /** Connecting to the meter. */
    CONNECT,

    /** Requesting the ATT MTU. */
    MTU,

    /** Discovering services. */
    DISCOVER,

    /** Reading a characteristic. */
    READ,

    /** Reading a descriptor (the GATT probe). */
    READ_DESCRIPTOR,

    /** Enabling notifications (CCCD write). */
    ENABLE_NOTIFICATIONS,

    /** Writing a characteristic. */
    WRITE,

    /** Waiting for an assignment result. */
    ASSIGNMENT_REPLY,

    /** Waiting for a command receipt. */
    COMMAND_RECEIPT,

    /** The whole setup, from link up to ready. */
    SETUP,
}

/** Why the link is not ready. Every error says how it recovers. */
public sealed interface LinkError {
    /** How the link recovers from this error. */
    public val recovery: Recovery

    /** Bluetooth is off. */
    public data object BluetoothOff : LinkError {
        override val recovery: Recovery = Recovery.WAIT_FOR_BLUETOOTH
    }

    /** The Bluetooth permission is not granted. */
    public data object PermissionMissing : LinkError {
        override val recovery: Recovery = Recovery.WAIT_FOR_USER
    }

    /** The bond with the meter is gone or its keys were rejected; the bike must be paired again. */
    public data object BondLost : LinkError {
        override val recovery: Recovery = Recovery.WAIT_FOR_USER
    }

    /** A connection attempt failed with this HCI/GATT status. */
    public data class ConnectFailed(
        /** The status from the Bluetooth stack. */
        val status: Int,
    ) : LinkError {
        override val recovery: Recovery = Recovery.BACKOFF
    }

    /** The link dropped during setup, with this status. */
    public data class Disconnected(
        /** The status from the Bluetooth stack. */
        val status: Int,
    ) : LinkError {
        override val recovery: Recovery = Recovery.BACKOFF
    }

    /** The device does not offer the meter's services. */
    public data object ServicesMissing : LinkError {
        override val recovery: Recovery = Recovery.BACKOFF
    }

    /** The meter refused an operation because the link is not authenticated (status 5, 15 or 137). */
    public data class AuthenticationFailed(
        /** The status from the Bluetooth stack. */
        val status: Int,
    ) : LinkError {
        override val recovery: Recovery = Recovery.BACKOFF
    }

    /** A GATT operation failed with this status. */
    public data class GattFailed(
        /** The operation. */
        val op: GattOp,
        /** The status from the Bluetooth stack. */
        val status: Int,
    ) : LinkError {
        override val recovery: Recovery = Recovery.BACKOFF
    }

    /** A GATT operation or the whole setup took too long. */
    public data class TimedOut(
        /** What timed out. */
        val op: GattOp,
    ) : LinkError {
        override val recovery: Recovery = Recovery.BACKOFF
    }

    /** The meter refused to register a switch function. */
    public data class MeterRefused(
        /** The function. */
        val function: FunctionId,
        /** The meter's answer. */
        val status: AssignmentStatus,
    ) : LinkError {
        override val recovery: Recovery = Recovery.BACKOFF
    }

    /** A bug in our code; logged with its stack trace, and the ride goes on. */
    public data class Unexpected(
        /** The exception. */
        val cause: Throwable,
    ) : LinkError {
        override val recovery: Recovery = Recovery.BACKOFF
    }
}

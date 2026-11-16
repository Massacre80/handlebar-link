package com.abid.handlebarlink.bike.link.spi

import kotlinx.coroutines.flow.Flow

// The engine's seams to the platform (docs/research/02-ble-connection.md §6.1): the "abstraction"
// side of Android's API/implementation module pattern (modularization guide, "common patterns").
// `:bike:link-android` implements them; tests use fakes. Implementations never block.

/** Opens GATT connections, one per generation. */
public interface GattRadio {
    /**
     * Starts connecting generation [gen] with a new GATT object (C2). Throws
     * [com.abid.handlebarlink.bike.link.LinkException] with `PermissionMissing` when not permitted.
     */
    public fun open(
        gen: Int,
        autoConnect: Boolean,
    ): GattConnection
}

/** One generation's connection: its GATT operations plus its connection-state changes. */
public interface GattConnection : GattLink {
    /** Connection-state changes of this generation, in order. One collector. */
    public val changes: Flow<ConnectionChange>

    /** Disconnects and releases this generation. Idempotent; never throws. */
    public fun close()
}

/** A change in one generation's connection. */
public sealed interface ConnectionChange {
    /** The link is up. */
    public data object Connected : ConnectionChange

    /** The link is down, with the stack's [status]. */
    public data class Disconnected(
        /** HCI/GATT status from the stack. */
        val status: Int,
    ) : ConnectionChange

    /** The meter's GATT table changed (`onServiceChanged`, C7). */
    public data object ServiceChanged : ConnectionChange
}

/** The phone's Current Time Service, which the meter reads to set its clock (SAB §4.6). */
public interface ClockServer {
    /** Opens the GATT server; false when the system refused (the link still works). */
    public fun open(): Boolean

    /** Closes it. Idempotent. */
    public fun close()
}

/** What the system reports about Bluetooth, the bond and the permission. */
public interface SystemSignals {
    /** The facts right now; read once when the link starts running. */
    public fun current(): SystemFacts

    /** Changes after that, in order. */
    public val changes: Flow<SystemChange>
}

/** The system facts the link needs before it may connect. */
public data class SystemFacts(
    /** Bluetooth is on. */
    val bluetoothOn: Boolean,
    /** The phone is bonded with the meter. */
    val bonded: Boolean,
    /** The Bluetooth permission is granted. */
    val permissionGranted: Boolean,
)

/** A change reported by the system. */
public sealed interface SystemChange {
    /** Bluetooth turned [on] or off. */
    public data class Bluetooth(
        /** Whether it is on now. */
        val on: Boolean,
    ) : SystemChange

    /** The bond with the meter appeared or disappeared. */
    public data class Bond(
        /** Whether the phone is bonded now. */
        val bonded: Boolean,
    ) : SystemChange

    /** The stack reported the meter's keys missing (`ACTION_KEY_MISSING`). */
    public data object KeyMissing : SystemChange

    /** The Bluetooth permission was granted or revoked. */
    public data class Permission(
        /** Whether it is granted now. */
        val granted: Boolean,
    ) : SystemChange
}

/** Keeps the CPU awake during setup only (edge case 12). */
public interface WakeLockPort {
    /** Acquires; released automatically after [timeoutMillis] at the latest. */
    public fun acquire(timeoutMillis: Long)

    /** Releases. Idempotent. */
    public fun release()
}

/** The platform implementations a link works with. */
public class LinkPorts(
    /** Opens connections. */
    public val radio: GattRadio,
    /** The clock server. */
    public val server: ClockServer,
    /** System facts and changes. */
    public val signals: SystemSignals,
    /** The setup wake lock. */
    public val wakeLock: WakeLockPort,
)

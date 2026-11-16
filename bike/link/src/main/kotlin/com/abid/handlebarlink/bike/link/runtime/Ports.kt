package com.abid.handlebarlink.bike.link.runtime

import com.abid.handlebarlink.bike.link.gatt.GattLink
import com.abid.handlebarlink.bike.link.machine.Facts
import kotlinx.coroutines.flow.Flow

// The runtime's seams to the platform (docs/research/02-ble-connection.md §6.1). Android implements
// them in M2.5; tests use fakes. Implementations never block and never throw except as documented.

/** Opens GATT connections, one per generation. */
internal interface GattRadio {
    /**
     * Starts connecting generation [gen] with a new GATT object (C2). Throws
     * [com.abid.handlebarlink.bike.link.LinkException] with PermissionMissing when not permitted.
     */
    fun open(
        gen: Int,
        autoConnect: Boolean,
    ): GattConnection
}

/** One generation's connection: its GATT operations plus its connection-state changes. */
internal interface GattConnection : GattLink {
    /** Connection-state changes of this generation, in order. Single collector. */
    val changes: Flow<ConnectionChange>

    /** Disconnects and releases this generation. Idempotent; never throws. */
    fun close()
}

/** A change in one generation's connection. */
internal sealed interface ConnectionChange {
    data object Connected : ConnectionChange

    data class Disconnected(
        val status: Int,
    ) : ConnectionChange

    /** The meter's GATT table changed (`onServiceChanged`, C7). */
    data object ServiceChanged : ConnectionChange
}

/** The phone's Current Time Service, which the meter reads to set its clock (SAB §4.6). */
internal interface ClockServer {
    /** Opens the GATT server; false when the system refused (the link still works). */
    fun open(): Boolean

    /** Closes it. Idempotent. */
    fun close()
}

/** What the system reports: Bluetooth, bond, keys, permission. */
internal interface SystemSignals {
    /** The facts right now; read once when the runtime starts. */
    fun current(): Facts

    /** Changes after that, in order. */
    val changes: Flow<SystemChange>
}

/** A change reported by the system. */
internal sealed interface SystemChange {
    data class Bluetooth(
        val on: Boolean,
    ) : SystemChange

    data class Bond(
        val bonded: Boolean,
    ) : SystemChange

    data object KeyMissing : SystemChange

    data class Permission(
        val granted: Boolean,
    ) : SystemChange
}

/** Keeps the CPU awake during setup only (edge case 12). */
internal interface WakeLockPort {
    /** Acquires, released automatically after [timeoutMillis] at the latest. */
    fun acquire(timeoutMillis: Long)

    /** Releases. Idempotent. */
    fun release()
}

/** The ports a runtime works with. */
internal class LinkPorts(
    val radio: GattRadio,
    val server: ClockServer,
    val signals: SystemSignals,
    val wakeLock: WakeLockPort,
)

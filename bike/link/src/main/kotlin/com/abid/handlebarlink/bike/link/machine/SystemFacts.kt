package com.abid.handlebarlink.bike.link.machine

import com.abid.handlebarlink.bike.link.LinkProblem

// Presence and what the system reports: Bluetooth, bond, keys, permission (§2.4, §2.7, §4.3).

/** Modes in which losing the bond changes nothing: not running, or already waiting for the rider. */
private val NOT_BONDABLE: Set<Mode> = setOf(Mode.Stopped, Mode.Dormant, Mode.PermissionMissing)

internal fun Next.onPresenceAppeared() {
    val mode = machine.mode
    machine = machine.copy(present = true)
    when {
        mode == Mode.Dormant -> {
            resetPresenceCycle()
            arm()
        }

        mode is Mode.Waiting -> {
            if (mode.lingering) resetPresenceCycle()
            leaveOpen()
            connectDirect(attempt = 1)
        }

        // While linked the meter stops advertising anyway (edge case 25); elsewhere the hint is kept.
        else -> {}
    }
}

internal fun Next.onPresenceGone() {
    machine = machine.copy(present = false)
    val mode = machine.mode
    val linger = config.lingerMillis
    if (mode is Mode.Waiting && !mode.lingering && linger != null) {
        emit(Effect.StartTimer(Timer.LINGER, linger))
        mode(mode.copy(lingering = true))
    }
}

internal fun Next.onBluetooth(on: Boolean) {
    machine = machine.copy(facts = machine.facts.copy(bluetoothOn = on))
    val mode = machine.mode
    if (!on && mode !in INACTIVE && mode != Mode.BluetoothOff) {
        // Every GATT object and the server die with the adapter (§2.7).
        leaveOpen()
        cancelAllTimers()
        emit(Effect.CloseServer)
        mode(Mode.BluetoothOff)
    } else if (on && mode == Mode.BluetoothOff) {
        // Bluetooth came back during a ride: the bike is probably right here.
        resetPresenceCycle()
        machine = machine.copy(present = true)
        arm()
    }
}

internal fun Next.onBond(bonded: Boolean) {
    machine = machine.copy(facts = machine.facts.copy(bonded = bonded))
    val mode = machine.mode
    if (!bonded && mode !in NOT_BONDABLE) {
        // No autoConnect without a bond (C8, C10).
        toBlocked(Mode.BondBroken, LinkProblem.NEEDS_REPAIR)
    } else if (bonded) {
        machine = machine.copy(authFailures = 0)
        if (mode == Mode.BondBroken) {
            emit(Effect.Problem(null))
            arm()
        }
    }
}

/** A missing-key report or an authentication failure; twice means the bond is broken (§4.3). */
internal fun Next.onAuthFailure() {
    machine = machine.copy(authFailures = machine.authFailures + 1)
    if (machine.authFailures >= Timeouts.AUTH_FAILURES_FOR_BROKEN_BOND && machine.mode !in INACTIVE) {
        toBlocked(Mode.BondBroken, LinkProblem.NEEDS_REPAIR)
    }
}

internal fun Next.onPermission(granted: Boolean) {
    machine = machine.copy(facts = machine.facts.copy(permissionGranted = granted))
    val mode = machine.mode
    if (!granted && mode != Mode.Stopped && mode != Mode.Dormant) {
        toBlocked(Mode.PermissionMissing, LinkProblem.PERMISSION_MISSING)
    } else if (granted && mode == Mode.PermissionMissing) {
        emit(Effect.Problem(null))
        arm()
    }
}

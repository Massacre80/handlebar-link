package com.abid.handlebarlink.bike.link.machine

import com.abid.handlebarlink.bike.link.LinkProblem

// Start, stop, the clock server and blocking (docs/research/02-ble-connection.md §2.4).

/** Modes in which the link is not trying to connect at all. */
internal val INACTIVE: Set<Mode> = setOf(Mode.Stopped, Mode.Dormant, Mode.PermissionMissing, Mode.BondBroken)

internal fun Next.onStart(presentHint: Boolean) {
    val mode = machine.mode
    machine = machine.copy(present = machine.present || presentHint)
    when {
        mode == Mode.Stopped || mode == Mode.Dormant -> {
            resetPresenceCycle()
            arm()
        }

        // A fresh sign that the bike is here: stop waiting, connect directly.
        mode is Mode.Waiting && presentHint -> {
            leaveOpen()
            connectDirect(attempt = 1)
        }

        // Duplicate triggers (CDM, boot, user) change nothing else (edge case 24).
        else -> {}
    }
}

/** Checks the prerequisites and opens the clock server; the first connect follows [onServerOpened]. */
internal fun Next.arm() {
    val facts = machine.facts
    when {
        !facts.permissionGranted -> {
            toBlocked(Mode.PermissionMissing, LinkProblem.PERMISSION_MISSING)
        }

        !facts.bluetoothOn -> {
            emit(Effect.Foreground(true))
            mode(Mode.BluetoothOff)
        }

        !facts.bonded -> {
            toBlocked(Mode.BondBroken, LinkProblem.NEEDS_REPAIR)
        }

        else -> {
            // The meter reads the clock about 1.1 s after the link comes up: open the server first (§2.8).
            emit(Effect.Foreground(true), Effect.OpenServer)
            mode(Mode.Arming)
        }
    }
}

/** Closes everything and stops. */
internal fun Next.shutDown() {
    leaveOpen()
    cancelAllTimers()
    emit(Effect.CloseServer, Effect.Problem(null), Effect.Foreground(false))
    mode(Mode.Stopped)
}

internal fun Next.onServerOpened(ok: Boolean) {
    // A closed server means a wrong meter clock, not a broken link: connect anyway, retry the server.
    if (!ok) emit(Effect.StartTimer(Timer.SERVER_RETRY, Timeouts.SERVER_RETRY_MS))
    if (machine.mode == Mode.Arming) connectFirst()
}

internal fun Next.onServerRetry() {
    if (machine.mode !in INACTIVE && machine.mode != Mode.BluetoothOff) emit(Effect.OpenServer)
}

/** Stops trying and waits for the rider: closes everything, shows the problem. */
internal fun Next.toBlocked(
    mode: Mode,
    problem: LinkProblem,
) {
    leaveOpen()
    cancelAllTimers()
    emit(Effect.CloseServer, Effect.Problem(problem), Effect.Foreground(false))
    mode(mode)
}

internal fun Next.cancelAllTimers() {
    Timer.entries.forEach { emit(Effect.CancelTimer(it)) }
}

/** A new ignition cycle, adapter session or stable link: failure counters start again (§2.7). */
internal fun Next.resetPresenceCycle() {
    if (machine.stuck) emit(Effect.Problem(null))
    machine = machine.copy(failures = 0, earlyErrors = emptyList(), stuck = false)
}

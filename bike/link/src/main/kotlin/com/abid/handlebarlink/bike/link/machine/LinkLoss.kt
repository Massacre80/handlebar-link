package com.abid.handlebarlink.bike.link.machine

import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkProblem

// Link loss and connect-status classification (docs/research/02-ble-connection.md §2.6).

/** HCI 0x08, connection (supervision) timeout: what switching the ignition off produces. */
private const val STATUS_SUPERVISION_TIMEOUT = 8

/** GATT_ERROR; before API 35 also the stack's own direct-connect timeout. */
private const val STATUS_GATT_ERROR = 133

/** GATT_CONNECTION_TIMEOUT (API 35+). */
private const val STATUS_CONNECTION_TIMEOUT = 147

/** GATT_FAILURE. */
private const val STATUS_GATT_FAILURE = 257

/** Statuses that, when immediate, point at a stuck stack if they pile up. */
private val EARLY_ERRORS = setOf(STATUS_GATT_ERROR, STATUS_GATT_FAILURE)

internal fun Next.onDisconnected(
    gen: Int,
    status: Int,
) {
    if (!isOpen(gen)) return
    when (val mode = machine.mode) {
        is Mode.Direct -> {
            directFailed(mode, status)
        }

        is Mode.Waiting -> {
            waitingFailed(mode, status)
        }

        is Mode.Linked -> {
            leaveOpen()
            fail(LinkError.Disconnected(status))
        }

        is Mode.Ready -> {
            // Link loss after ready never backs off: reconnecting after a stall must be instant (§2.6).
            leaveOpen()
            if (status == STATUS_SUPERVISION_TIMEOUT) machine = machine.copy(present = false)
            armWaiting()
        }

        else -> {}
    }
}

private fun Next.directFailed(
    mode: Mode.Direct,
    status: Int,
) {
    emit(Effect.CancelTimer(Timer.CONNECT_TIMEOUT), Effect.Close(mode.gen))
    val elapsed = now - mode.startedAt
    val stackTimeout =
        status == STATUS_CONNECTION_TIMEOUT ||
            (status == STATUS_GATT_ERROR && elapsed >= Timeouts.CONNECT_TIMEOUT_STATUS_AFTER_MS)
    when {
        // The meter is not reachable: wait for it instead of hammering (C4).
        stackTimeout -> {
            armWaiting()
        }

        recordEarlyError(status) -> {
            spaceOut(LinkError.ConnectFailed(status))
        }

        mode.attempt < Timeouts.DIRECT_ATTEMPTS -> {
            emit(Effect.StartTimer(Timer.DIRECT_RETRY, Timeouts.DIRECT_RETRY_STEP_MS * mode.attempt))
            mode(Mode.DirectPause(mode.attempt + 1))
        }

        else -> {
            armWaiting()
        }
    }
}

private fun Next.waitingFailed(
    mode: Mode.Waiting,
    status: Int,
) {
    emit(Effect.Close(mode.gen))
    when {
        recordEarlyError(status) -> spaceOut(LinkError.ConnectFailed(status))
        now - mode.armedAt < Timeouts.QUICK_FAILURE_MS -> fail(LinkError.ConnectFailed(status))
        else -> armWaiting(keepLingering = mode.lingering)
    }
}

internal fun Next.onLingerElapsed() {
    val mode = machine.mode
    if (mode !is Mode.Waiting || !mode.lingering) return
    leaveOpen()
    emit(Effect.CloseServer, Effect.Foreground(false))
    mode(Mode.Dormant)
}

/** Closes the open generation (if any) and stops its timers and wake lock. */
internal fun Next.leaveOpen() {
    val mode = machine.mode
    mode.gen?.let { emit(Effect.Close(it)) }
    if (mode is Mode.Linked) emit(Effect.WakeLock(false))
    emit(
        Effect.CancelTimer(Timer.CONNECT_TIMEOUT),
        Effect.CancelTimer(Timer.SETUP_WATCHDOG),
        Effect.CancelTimer(Timer.READY_STABLE),
    )
}

/**
 * Records an immediate connect error. Returns true while the stack looks stuck (6 within 2 min,
 * §2.6); the rider is told once, and re-arms are spaced out.
 */
private fun Next.recordEarlyError(status: Int): Boolean {
    if (status !in EARLY_ERRORS) return machine.stuck
    val recent = (machine.earlyErrors + now).filter { now - it < Timeouts.EARLY_ERROR_WINDOW_MS }
    machine = machine.copy(earlyErrors = recent)
    if (!machine.stuck && recent.size >= Timeouts.EARLY_ERRORS_FOR_STUCK) {
        machine = machine.copy(stuck = true)
        emit(Effect.Problem(LinkProblem.BLUETOOTH_STUCK))
    }
    return machine.stuck
}

/** While the stack is stuck: wait [Timeouts.STUCK_REARM_MS] between re-arms, without counting failures. */
private fun Next.spaceOut(error: LinkError) {
    emit(Effect.StartTimer(Timer.BACKOFF, Timeouts.STUCK_REARM_MS))
    mode(Mode.Backoff(error, machine.failures, until = now + Timeouts.STUCK_REARM_MS))
}

package com.abid.handlebarlink.bike.link.machine

import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.MeterInfo
import com.abid.handlebarlink.bike.link.SetupStep

// Setup progress, ready, failures and backoff (docs/research/02-ble-connection.md §2.4–2.6).

internal fun Next.onSetupProgress(
    gen: Int,
    step: SetupStep,
) {
    val mode = machine.mode
    if (mode is Mode.Linked && mode.gen == gen) mode(mode.copy(step = step))
}

internal fun Next.onSetupDone(
    gen: Int,
    meter: MeterInfo,
) {
    val mode = machine.mode
    if (mode !is Mode.Linked || mode.gen != gen) return
    machine = machine.copy(authFailures = 0)
    emit(
        Effect.CancelTimer(Timer.SETUP_WATCHDOG),
        Effect.WakeLock(false),
        Effect.StartTimer(Timer.READY_STABLE, Timeouts.READY_STABLE_MS, gen),
    )
    mode(Mode.Ready(gen, meter, since = now))
}

internal fun Next.onSetupFailed(
    gen: Int,
    error: LinkError,
) {
    val mode = machine.mode
    if (!isOpen(gen) || (mode !is Mode.Linked && mode !is Mode.Ready)) return
    leaveOpen()
    if (error is LinkError.AuthenticationFailed) {
        onAuthFailure()
        if (machine.mode == Mode.BondBroken) return
    }
    fail(error)
}

/** The meter's GATT table changed: discover again and set up again (C7). */
internal fun Next.onServiceChanged(gen: Int) {
    val mode = machine.mode
    if (!isOpen(gen) || (mode !is Mode.Linked && mode !is Mode.Ready)) return
    emit(
        Effect.CancelTimer(Timer.READY_STABLE),
        Effect.WakeLock(true),
        Effect.RunSetup(gen),
        Effect.StartTimer(Timer.SETUP_WATCHDOG, Timeouts.SETUP_WATCHDOG_MS, gen),
    )
    mode(Mode.Linked(gen, SetupStep.DISCOVER))
}

internal fun Next.onSetupWatchdog(gen: Int?) {
    val mode = machine.mode
    if (mode !is Mode.Linked || mode.gen != gen) return
    leaveOpen()
    fail(LinkError.TimedOut(GattOp.SETUP))
}

/** Ready long enough: the failure counters start again. */
internal fun Next.onReadyStable(gen: Int?) {
    val mode = machine.mode
    if (mode is Mode.Ready && mode.gen == gen) resetPresenceCycle()
}

internal fun Next.onBackoffElapsed() {
    if (machine.mode is Mode.Backoff) connectFirst()
}

/** A setup failure: back off 1, 2, 4, 8, 16, 30, 30 … seconds (jittered by the runtime). */
internal fun Next.fail(error: LinkError) {
    val failures = machine.failures + 1
    val delay = backoffDelay(failures)
    machine = machine.copy(failures = failures)
    emit(Effect.StartTimer(Timer.BACKOFF, delay, jitter = true))
    mode(Mode.Backoff(error, failures, until = now + delay))
}

/** The base backoff before attempt [failures] + 1. */
internal fun backoffDelay(failures: Int): Long {
    val doublings = (failures - 1).coerceIn(0, MAX_DOUBLINGS)
    return (Timeouts.BACKOFF_FIRST_MS shl doublings).coerceAtMost(Timeouts.BACKOFF_MAX_MS)
}

/** 2^5 s already exceeds the cap; more doublings would only risk overflow. */
private const val MAX_DOUBLINGS = 5

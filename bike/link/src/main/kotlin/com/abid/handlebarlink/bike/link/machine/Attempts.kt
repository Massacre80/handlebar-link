package com.abid.handlebarlink.bike.link.machine

import com.abid.handlebarlink.bike.link.SetupStep

// Connection attempts: direct first, then autoConnect (docs/research/02-ble-connection.md §2.4, C2, C5).

/** The first connect after arming: directly when the bike is believed present, else wait for it. */
internal fun Next.connectFirst() {
    if (machine.present) connectDirect(attempt = 1) else armWaiting()
}

/** A direct (autoConnect = false) attempt on a new generation. */
internal fun Next.connectDirect(attempt: Int) {
    val gen = newGen()
    emit(
        Effect.Connect(gen, autoConnect = false),
        Effect.StartTimer(Timer.CONNECT_TIMEOUT, Timeouts.DIRECT_CONNECT_MS, gen),
    )
    mode(Mode.Direct(gen, attempt, startedAt = now))
}

/**
 * An autoConnect connection on a new generation: the controller connects when the meter advertises.
 * [keepLingering]: a re-arm while already lingering keeps the running linger timer.
 */
internal fun Next.armWaiting(keepLingering: Boolean = false) {
    val gen = newGen()
    emit(Effect.Connect(gen, autoConnect = true))
    val lingerTimer = config.lingerMillis?.takeIf { !keepLingering && !machine.present }
    lingerTimer?.let { emit(Effect.StartTimer(Timer.LINGER, it)) }
    mode(Mode.Waiting(gen, armedAt = now, lingering = keepLingering || lingerTimer != null))
}

internal fun Next.onConnected(gen: Int) {
    val mode = machine.mode
    if (!isOpen(gen) || (mode !is Mode.Direct && mode !is Mode.Waiting)) return
    machine = machine.copy(present = true)
    emit(
        Effect.CancelTimer(Timer.CONNECT_TIMEOUT),
        Effect.CancelTimer(Timer.LINGER),
        Effect.WakeLock(true),
        Effect.RunSetup(gen),
        Effect.StartTimer(Timer.SETUP_WATCHDOG, Timeouts.SETUP_WATCHDOG_MS, gen),
    )
    mode(Mode.Linked(gen, SetupStep.BOND))
}

internal fun Next.onConnectTimeout(gen: Int?) {
    val mode = machine.mode
    if (mode !is Mode.Direct || mode.gen != gen) return
    emit(Effect.Close(mode.gen))
    armWaiting()
}

internal fun Next.onDirectRetry() {
    val mode = machine.mode
    if (mode is Mode.DirectPause) connectDirect(mode.attempt)
}

private fun Next.newGen(): Int {
    val gen = machine.nextGen
    machine = machine.copy(nextGen = gen + 1)
    return gen
}

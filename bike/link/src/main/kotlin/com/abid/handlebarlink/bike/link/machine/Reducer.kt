package com.abid.handlebarlink.bike.link.machine

/** Policy settings of the machine. */
internal data class MachineConfig(
    /**
     * After the link is lost and the bike is gone, keep waiting this long before going dormant.
     * Null: never go dormant (used until companion-device presence can wake the app, M4).
     */
    val lingerMillis: Long? = null,
)

/** Applies [event] to [machine] at monotonic time [now]. Pure and synchronous. */
internal fun reduce(
    machine: Machine,
    event: Event,
    now: Long,
    config: MachineConfig,
): Transition {
    val next = Next(machine, now, config)
    when (event) {
        is Event.Start -> next.onStart(event.presentHint)
        Event.Stop -> next.shutDown()
        Event.PresenceAppeared -> next.onPresenceAppeared()
        Event.PresenceGone -> next.onPresenceGone()
        is Event.Bluetooth -> next.onBluetooth(event.on)
        is Event.Bond -> next.onBond(event.bonded)
        Event.KeyMissing -> next.onAuthFailure()
        is Event.Permission -> next.onPermission(event.granted)
        is Event.ServerOpened -> next.onServerOpened(event.ok)
        is Event.Connected -> next.onConnected(event.gen)
        is Event.Disconnected -> next.onDisconnected(event.gen, event.status)
        is Event.SetupProgress -> next.onSetupProgress(event.gen, event.step)
        is Event.SetupDone -> next.onSetupDone(event.gen, event.meter)
        is Event.SetupFailed -> next.onSetupFailed(event.gen, event.error)
        is Event.ServiceChanged -> next.onServiceChanged(event.gen)
        is Event.TimerFired -> next.onTimer(event.timer, event.gen)
    }
    return Transition(next.machine, next.effects)
}

/** The transition being built: the new machine plus the effects, in order. */
internal class Next(
    var machine: Machine,
    val now: Long,
    val config: MachineConfig,
) {
    val effects: MutableList<Effect> = mutableListOf()

    fun emit(vararg effect: Effect) {
        effects += effect
    }

    fun mode(mode: Mode) {
        machine = machine.copy(mode = mode)
    }

    /** Whether [gen] is the open connection; events from closed generations are ignored (C3). */
    fun isOpen(gen: Int): Boolean = machine.openGen == gen
}

private fun Next.onTimer(
    timer: Timer,
    gen: Int?,
) {
    when (timer) {
        Timer.CONNECT_TIMEOUT -> onConnectTimeout(gen)
        Timer.DIRECT_RETRY -> onDirectRetry()
        Timer.BACKOFF -> onBackoffElapsed()
        Timer.LINGER -> onLingerElapsed()
        Timer.SETUP_WATCHDOG -> onSetupWatchdog(gen)
        Timer.READY_STABLE -> onReadyStable(gen)
        Timer.SERVER_RETRY -> onServerRetry()
    }
}

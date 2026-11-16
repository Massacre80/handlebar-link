package com.abid.handlebarlink.bike.link.machine

import com.abid.handlebarlink.bike.link.MeterInfo
import com.abid.handlebarlink.bike.link.SetupStep

/** Feeds events into [reduce] at chosen monotonic times and records every effect. */
internal class Harness(
    private val config: MachineConfig = MachineConfig(),
    facts: Facts = Facts(bluetoothOn = true, bonded = true, permissionGranted = true),
) {
    var machine = Machine(facts = facts)
        private set
    var now = 0L
        private set
    val all = mutableListOf<Effect>()

    val mode: Mode get() = machine.mode

    /** Sends [event] after [after] ms; returns its effects. */
    fun send(
        event: Event,
        after: Long = 0,
    ): List<Effect> {
        now += after
        val transition = reduce(machine, event, now, config)
        machine = transition.machine
        all += transition.effects
        return transition.effects
    }

    /** The open generation, which must exist. */
    val gen: Int get() = checkNotNull(machine.openGen) { "no open generation in $mode" }

    /** Start → server → direct connect → linked → ready on the first generation. */
    fun toReady(meter: MeterInfo = METER): Harness {
        send(Event.Start(presentHint = true))
        send(Event.ServerOpened(ok = true))
        send(Event.Connected(gen), after = 1_200)
        SetupStep.entries.forEach { send(Event.SetupProgress(gen, it)) }
        send(Event.SetupDone(gen, meter), after = 2_200)
        return this
    }

    companion object {
        val METER = MeterInfo(seriesCode = null, displayCapability = null)
    }
}

internal fun List<Effect>.connects(): List<Effect.Connect> = filterIsInstance<Effect.Connect>()

internal fun List<Effect>.timers(timer: Timer): List<Effect.StartTimer> =
    filterIsInstance<Effect.StartTimer>().filter { it.timer == timer }

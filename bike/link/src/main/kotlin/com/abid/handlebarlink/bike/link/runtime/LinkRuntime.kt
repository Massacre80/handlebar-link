package com.abid.handlebarlink.bike.link.runtime

import com.abid.handlebarlink.bike.link.BikeLink
import com.abid.handlebarlink.bike.link.BikeLinkConfig
import com.abid.handlebarlink.bike.link.ButtonEvent
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.bike.link.machine.Event
import com.abid.handlebarlink.bike.link.machine.Facts
import com.abid.handlebarlink.bike.link.machine.Machine
import com.abid.handlebarlink.bike.link.machine.MachineConfig
import com.abid.handlebarlink.bike.link.machine.reduce
import com.abid.handlebarlink.bike.link.machine.toLinkState
import com.abid.handlebarlink.bike.link.spi.LinkPorts
import com.abid.handlebarlink.bike.link.spi.SystemChange
import com.abid.handlebarlink.bike.link.spi.SystemFacts
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The [BikeLink] implementation: an actor that feeds events through the pure machine and runs its
 * effects (docs/research/02-ble-connection.md §2.1, §6.1). All link state lives on [dispatcher], which
 * must be single-threaded; platform callbacks and commands only queue events.
 */
internal class LinkRuntime(
    private val ports: LinkPorts,
    private val dispatcher: CoroutineDispatcher,
    private val time: TimeProvider,
    private val random: Random,
    private val log: Logger,
    config: BikeLinkConfig,
) : BikeLink {
    private val machineConfig = MachineConfig(lingerMillis = config.lingerMillis)
    private val inbox = Channel<Event>(Channel.UNLIMITED)
    private val running = AtomicBoolean(false)

    private val stateFlow = MutableStateFlow<LinkState>(LinkState.Stopped)
    private val keepAliveFlow = MutableStateFlow(false)
    private val problemFlow = MutableStateFlow<LinkProblem?>(null)
    private val buttonFlow =
        MutableSharedFlow<ButtonEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Generations stay unique across runs, so a stale queued event can never match a new connection. */
    private var nextGen = 1

    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val keepAlive: StateFlow<Boolean> = keepAliveFlow.asStateFlow()
    override val problem: StateFlow<LinkProblem?> = problemFlow.asStateFlow()
    override val buttons: Flow<ButtonEvent> = buttonFlow.asSharedFlow()

    override fun start(presentHint: Boolean) {
        inbox.trySend(Event.Start(presentHint))
    }

    override fun stop() {
        inbox.trySend(Event.Stop)
    }

    override fun onPresence(present: Boolean) {
        inbox.trySend(if (present) Event.PresenceAppeared else Event.PresenceGone)
    }

    override suspend fun run() {
        check(running.compareAndSet(false, true)) { "BikeLink.run() is already running" }
        try {
            withContext(dispatcher) { coroutineScope { loop(this) } }
        } finally {
            running.set(false)
        }
    }

    private suspend fun loop(scope: CoroutineScope) {
        val outputs = EffectRunner.Outputs(keepAliveFlow, problemFlow, buttonFlow)
        val runner = EffectRunner(scope, ports, { inbox.trySend(it) }, outputs, RuntimeEnv(time, random, log))
        var machine = Machine(facts = ports.signals.current().toFacts(), nextGen = nextGen)
        scope.launch { ports.signals.changes.collect { inbox.send(it.toEvent()) } }
        log.info("link.run.start", "facts" to machine.facts)
        try {
            for (event in inbox) {
                machine = step(machine, event, runner)
                nextGen = machine.nextGen
            }
        } finally {
            withContext(NonCancellable) {
                runner.closeAll()
                keepAliveFlow.value = false
                stateFlow.value = LinkState.Stopped
                log.info("link.run.end")
            }
        }
    }

    private fun step(
        machine: Machine,
        event: Event,
        runner: EffectRunner,
    ): Machine {
        val now = time.elapsedMillis()
        val transition = reduce(machine, event, now, machineConfig)
        if (transition.machine.mode != machine.mode) {
            log.info("link.state", "event" to event, "mode" to transition.machine.mode)
        }
        transition.effects.forEach(runner::run)
        stateFlow.value = transition.machine.mode.toLinkState(now, time.clock.instant())
        return transition.machine
    }

    private fun SystemFacts.toFacts(): Facts = Facts(bluetoothOn, bonded, permissionGranted)

    private fun SystemChange.toEvent(): Event =
        when (this) {
            is SystemChange.Bluetooth -> Event.Bluetooth(on)
            is SystemChange.Bond -> Event.Bond(bonded)
            SystemChange.KeyMissing -> Event.KeyMissing
            is SystemChange.Permission -> Event.Permission(granted)
        }
}

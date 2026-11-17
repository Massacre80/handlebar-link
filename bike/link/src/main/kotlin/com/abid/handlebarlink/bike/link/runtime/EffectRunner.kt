package com.abid.handlebarlink.bike.link.runtime

import com.abid.handlebarlink.bike.link.ButtonEvent
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.machine.Effect
import com.abid.handlebarlink.bike.link.machine.Event
import com.abid.handlebarlink.bike.link.machine.Timeouts
import com.abid.handlebarlink.bike.link.machine.Timer
import com.abid.handlebarlink.bike.link.session.MeterSession
import com.abid.handlebarlink.bike.link.spi.ConnectionChange
import com.abid.handlebarlink.bike.link.spi.GattConnection
import com.abid.handlebarlink.bike.link.spi.LinkPorts
import com.abid.handlebarlink.bike.protocol.FunctionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Carries out the machine's effects. Lives for one [LinkRuntime.run]; every job it starts is a child
 * of that run's [scope], and all of its state is touched only on the runtime's single dispatcher.
 */
internal class EffectRunner(
    private val scope: CoroutineScope,
    private val ports: LinkPorts,
    private val post: (Event) -> Unit,
    private val outputs: Outputs,
    private val env: RuntimeEnv,
) {
    private val log = env.log

    /** What the runtime publishes besides the state. */
    class Outputs(
        val keepAlive: MutableStateFlow<Boolean>,
        val problem: MutableStateFlow<LinkProblem?>,
        val buttons: MutableSharedFlow<ButtonEvent>,
    )

    /** One open generation: its connection and the jobs that die with it. */
    private class Generation(
        val connection: GattConnection,
        val watcher: Job,
    ) {
        var setup: Job? = null
        var presses: Job? = null
    }

    private val generations = mutableMapOf<Int, Generation>()
    private val timers = mutableMapOf<Timer, Job>()

    fun run(effect: Effect) {
        when (effect) {
            is Effect.Foreground -> {
                outputs.keepAlive.value = effect.on
            }

            is Effect.Problem -> {
                outputs.problem.value = effect.problem
            }

            Effect.OpenServer -> {
                post(Event.ServerOpened(ports.server.open()))
            }

            Effect.CloseServer -> {
                ports.server.close()
            }

            is Effect.Connect -> {
                connect(effect.gen, effect.autoConnect)
            }

            is Effect.Close -> {
                close(effect.gen)
            }

            is Effect.RunSetup -> {
                runSetup(effect.gen)
            }

            is Effect.StartTimer -> {
                startTimer(effect)
            }

            is Effect.CancelTimer -> {
                timers.remove(effect.timer)?.cancel()
            }

            is Effect.WakeLock -> {
                if (effect.on) {
                    ports.wakeLock.acquire(
                        Timeouts.SETUP_WATCHDOG_MS,
                    )
                } else {
                    ports.wakeLock.release()
                }
            }
        }
    }

    /** Closes everything this run opened. Called once, when the run ends. */
    fun closeAll() {
        generations.keys.toList().forEach(::close)
        timers.values.forEach { it.cancel() }
        timers.clear()
        ports.server.close()
        ports.wakeLock.release()
    }

    private fun connect(
        gen: Int,
        autoConnect: Boolean,
    ) {
        val connection =
            try {
                ports.radio.open(gen, autoConnect)
            } catch (e: LinkException) {
                log.warn("link.connect.refused", "gen" to gen, "error" to e.error)
                post(
                    if (e.error ==
                        LinkError.PermissionMissing
                    ) {
                        Event.Permission(false)
                    } else {
                        Event.Disconnected(gen, GATT_FAILURE)
                    },
                )
                return
            }
        val watcher =
            scope.launch {
                connection.changes.collect { change ->
                    post(
                        when (change) {
                            ConnectionChange.Connected -> Event.Connected(gen)
                            is ConnectionChange.Disconnected -> Event.Disconnected(gen, change.status)
                            ConnectionChange.ServiceChanged -> Event.ServiceChanged(gen)
                        },
                    )
                }
            }
        generations[gen] = Generation(connection, watcher)
    }

    private fun close(gen: Int) {
        val generation = generations.remove(gen) ?: return
        generation.setup?.cancel()
        generation.presses?.cancel()
        generation.watcher.cancel()
        generation.connection.close()
    }

    /** Runs a setup session on [gen]; its result comes back as an event. */
    private fun runSetup(gen: Int) {
        val generation = generations[gen] ?: return
        generation.setup?.cancel()
        val session = MeterSession(generation.connection, log)
        if (generation.presses == null) {
            // Subscribed before the setup writes anything, so the meter's first events are not missed.
            generation.presses = scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect(::forward) }
        }
        generation.setup = scope.launch { post(setUp(gen, session)) }
    }

    /**
     * The runtime's one error boundary (AGENTS.md "Engine architecture"): typed failures become
     * events, cancellation propagates, and a bug becomes [LinkError.Unexpected] instead of ending the ride.
     */
    @Suppress("TooGenericExceptionCaught") // The single documented boundary; everything else catches specific types.
    private suspend fun setUp(
        gen: Int,
        session: MeterSession,
    ): Event =
        try {
            Event.SetupDone(gen, session.setUp { post(Event.SetupProgress(gen, it)) })
        } catch (e: LinkException) {
            Event.SetupFailed(gen, e.error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("link.setup.bug", e, "gen" to gen)
            Event.SetupFailed(gen, LinkError.Unexpected(e))
        }

    private fun forward(event: FunctionEvent) {
        when (event) {
            is FunctionEvent.Press -> {
                log.info("button.press", "switch" to event.switch)
                outputs.buttons.tryEmit(ButtonEvent(event.switch, env.time.clock.instant()))
            }

            is FunctionEvent.MeterMode -> {
                log.info("meter.mode", "phone" to event.phoneMode)
            }

            is FunctionEvent.Other -> {
                log.debug("meter.function", "function" to event.function)
            }

            is FunctionEvent.Unknown -> {
                log.warn("meter.function.unknown", "value" to event.hex)
            }
        }
    }

    private fun startTimer(effect: Effect.StartTimer) {
        timers.remove(effect.timer)?.cancel()
        val delayMillis = if (effect.jitter) jittered(effect.delayMillis) else effect.delayMillis
        timers[effect.timer] =
            scope.launch {
                delay(delayMillis)
                timers.remove(effect.timer)
                post(Event.TimerFired(effect.timer, effect.gen))
            }
    }

    /** ±20 % (docs/research/02-ble-connection.md §2.6). */
    private fun jittered(millis: Long): Long = (millis * (JITTER_LOW + env.random.nextDouble() * JITTER_SPAN)).toLong()

    private companion object {
        /** GATT_FAILURE, for a connection the stack would not even start. */
        const val GATT_FAILURE = 257
        const val JITTER_LOW = 0.8
        const val JITTER_SPAN = 0.4
    }
}

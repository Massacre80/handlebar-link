package com.abid.handlebarlink.carplay.engine.runtime

import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.session.WirelessAttempt
import com.abid.handlebarlink.carplay.engine.spi.CarPlayPorts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The [CarPlayLink] implementation: an actor on [dispatcher] (single-threaded) that runs one
 * [WirelessAttempt] at a time and retries with growing, jittered pauses while started.
 *
 * Each attempt has a generation; events from an older one are ignored, so a failure that arrives after
 * [stop] can never start a new attempt. A new attempt first waits for the previous one to finish closing
 * its radio and sockets. [keepAlive] stays true until that teardown is done.
 */
internal class CarPlayRuntime(
    private val ports: CarPlayPorts,
    private val dispatcher: CoroutineDispatcher,
    private val env: RuntimeEnv,
    private val config: CarPlayConfig,
) : CarPlayLink {
    private val inbox = Channel<Event>(Channel.UNLIMITED)
    private val running = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow<CarPlayState>(CarPlayState.Stopped)
    private val keepAliveFlow = MutableStateFlow(false)

    /** Unique across runs, so a stale event can never match a new attempt. */
    private var generation = 0

    override val state: StateFlow<CarPlayState> = stateFlow.asStateFlow()
    override val keepAlive: StateFlow<Boolean> = keepAliveFlow.asStateFlow()

    override fun start() {
        inbox.trySend(Event.Start)
    }

    override fun stop() {
        inbox.trySend(Event.Stop)
    }

    override suspend fun run() {
        check(running.compareAndSet(false, true)) { "CarPlayLink.run() is already running" }
        try {
            withContext(dispatcher) { coroutineScope { loop(Loop(this)) } }
        } finally {
            running.set(false)
        }
    }

    private class Loop(
        val scope: CoroutineScope,
    ) {
        var started = false
        var job: Job? = null
        var current = 0
        var failures = 0
    }

    private suspend fun loop(loop: Loop) {
        stateFlow.value = CarPlayState.Idle
        env.log.info("carplay.run.start")
        try {
            for (event in inbox) handle(event, loop)
        } finally {
            withContext(NonCancellable) {
                loop.job?.cancel()
                loop.job?.join()
                keepAliveFlow.value = false
                stateFlow.value = CarPlayState.Stopped
                env.log.info("carplay.run.end")
            }
        }
    }

    private fun handle(
        event: Event,
        loop: Loop,
    ) {
        when (event) {
            Event.Start -> {
                if (!loop.started) begin(loop)
            }

            Event.Stop -> {
                if (loop.started) end(loop)
            }

            is Event.Step -> {
                if (event.generation == loop.current && loop.started) {
                    env.log.info("carplay.step", "step" to event.step)
                    stateFlow.value = CarPlayState.Connecting(event.step)
                }
            }

            is Event.Connected -> {
                if (event.generation == loop.current && loop.started) connected(loop, event.phone)
            }

            is Event.Ended -> {
                if (event.generation == loop.current) ended(loop, event.error)
            }
        }
    }

    private fun begin(loop: Loop) {
        loop.started = true
        loop.failures = 0
        keepAliveFlow.value = true
        launchAttempt(loop, pause = 0)
    }

    private fun end(loop: Loop) {
        loop.started = false
        loop.job?.cancel()
        stateFlow.value = CarPlayState.Idle
        env.log.info("carplay.stopped")
    }

    private fun connected(
        loop: Loop,
        phone: String,
    ) {
        loop.failures = 0
        stateFlow.value = CarPlayState.Connected(phone, env.time.clock.instant())
        env.log.info("carplay.connected", "phone" to phone)
    }

    private fun ended(
        loop: Loop,
        error: CarPlayError?,
    ) {
        loop.job = null
        if (!loop.started || error == null) {
            keepAliveFlow.value = loop.started
            return
        }
        loop.failures++
        val pause = backoffMillis(loop.failures)
        stateFlow.value =
            CarPlayState.Recovering(
                error,
                loop.failures,
                env.time.clock
                    .instant()
                    .plusMillis(pause),
            )
        env.log.warn("carplay.recovering", "error" to error, "attempt" to loop.failures, "pause_ms" to pause)
        launchAttempt(loop, pause)
    }

    private fun launchAttempt(
        loop: Loop,
        pause: Long,
    ) {
        val generation = ++generation
        val previous = loop.job
        loop.current = generation
        loop.job =
            loop.scope.launch {
                var error: CarPlayError? = null
                try {
                    previous?.join()
                    error = attempt(generation, pause)
                } finally {
                    // Also after cancellation: the loop must learn the attempt has finished closing.
                    inbox.trySend(Event.Ended(generation, error))
                }
            }
    }

    /** Runs one attempt; returns why it ended (cancellation propagates). */
    @Suppress("TooGenericExceptionCaught") // The one runtime boundary: a bug becomes Unexpected and a retry.
    private suspend fun attempt(
        generation: Int,
        pause: Long,
    ): CarPlayError =
        try {
            delay(pause)
            WirelessAttempt(
                ports,
                config,
                env,
                onStep = { inbox.trySend(Event.Step(generation, it)) },
                onConnected = { inbox.trySend(Event.Connected(generation, it)) },
            ).run()
        } catch (e: CancellationException) {
            // Ours (stop, or run cancelled): propagate. A stray one from a port (a withTimeout inside it) while we
            // are still active would otherwise end the attempt silently with no retry (docs/LESSONS.md 48).
            currentCoroutineContext().ensureActive()
            env.log.error("carplay.unexpected", e)
            CarPlayError.Unexpected(e)
        } catch (e: CarPlayException) {
            e.error
        } catch (e: Exception) {
            env.log.error("carplay.unexpected", e)
            CarPlayError.Unexpected(e)
        }

    /** 2 s doubling to 30 s (DiPlay HOST:6298), ±20 % jitter so a flapping link does not retry in lockstep. */
    private fun backoffMillis(failures: Int): Long {
        val base = min(BASE_PAUSE * (1L shl min(failures - 1, MAX_SHIFT)), MAX_PAUSE)
        return (base * (1 + JITTER * (2 * env.random.nextDouble() - 1))).roundToLong()
    }

    private sealed interface Event {
        data object Start : Event

        data object Stop : Event

        data class Step(
            val generation: Int,
            val step: ConnectStep,
        ) : Event

        data class Connected(
            val generation: Int,
            val phone: String,
        ) : Event

        data class Ended(
            val generation: Int,
            val error: CarPlayError?,
        ) : Event
    }

    private companion object {
        const val BASE_PAUSE = 2_000L
        const val MAX_PAUSE = 30_000L
        const val MAX_SHIFT = 4
        const val JITTER = 0.2
    }
}

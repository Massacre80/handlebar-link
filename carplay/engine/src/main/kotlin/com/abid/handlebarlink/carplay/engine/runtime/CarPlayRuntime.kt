package com.abid.handlebarlink.carplay.engine.runtime

import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.CarPlayKey
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.TouchContact
import com.abid.handlebarlink.carplay.engine.session.CallStates
import com.abid.handlebarlink.carplay.engine.session.ControlInput
import com.abid.handlebarlink.carplay.engine.session.MediaFactory
import com.abid.handlebarlink.carplay.engine.session.SessionPlugins
import com.abid.handlebarlink.carplay.engine.session.TouchInput
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
 *
 * Retries (docs/research/carplay-spec/E-connection.md): a dial the iPhone did not answer is retried after 1.5 s
 * up to [QUICK_DIALS] failures in a row (#6); a retry that waits only because Bluetooth was off runs as soon as it
 * turns on (#5); everything else backs off 2 s doubling to 30 s.
 */
internal class CarPlayRuntime(
    private val ports: CarPlayPorts,
    private val dispatcher: CoroutineDispatcher,
    private val env: RuntimeEnv,
    /** Read at the start of each attempt, on [dispatcher] (createCarPlayLink). */
    private val config: () -> CarPlayConfig,
    /** Builds each AirPlay session's media seams: the timing exchange, the screen reader, the audio and microphone. */
    private val media: MediaFactory = MediaFactory.receivers(ports.screen, ports.audio, ports.microphone),
) : CarPlayLink {
    private val inbox = Channel<Event>(Channel.UNLIMITED)
    private val running = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow<CarPlayState>(CarPlayState.Stopped)
    private val keepAliveFlow = MutableStateFlow(false)
    private val touches = TouchInput()
    private val controls = ControlInput()
    private val callStates = CallStates(env.log)
    private val plugins = SessionPlugins(media, touches, controls, callStates)

    /** Unique across runs, so a stale event can never match a new attempt. */
    private var generation = 0

    override val state: StateFlow<CarPlayState> = stateFlow.asStateFlow()
    override val keepAlive: StateFlow<Boolean> = keepAliveFlow.asStateFlow()
    override val calls: StateFlow<List<CarPlayCall>> = callStates.current

    override fun start() {
        inbox.trySend(Event.Start)
    }

    override fun stop() {
        inbox.trySend(Event.Stop)
    }

    override fun touch(contacts: List<TouchContact>) {
        touches.offer(contacts)
    }

    override fun press(key: CarPlayKey) {
        controls.press(key)
    }

    override fun requestSiri() {
        controls.requestSiri()
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

        /** Why the last attempt failed, and when its retry starts (elapsed time); null once connected. */
        var lastError: CarPlayError? = null
        var retryAt = 0L
    }

    private suspend fun loop(loop: Loop) {
        stateFlow.value = CarPlayState.Idle
        env.log.info("carplay.run.start")
        // A platform signal: only trySend, the loop decides (AGENTS.md, engine architecture).
        val radio = loop.scope.launch { ports.bluetooth.radioOn.collect { inbox.trySend(Event.BluetoothOn) } }
        try {
            for (event in inbox) handle(event, loop)
        } finally {
            withContext(NonCancellable) {
                radio.cancel()
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
                if (event.generation == loop.current && loop.started) connected(loop, event.phone, event.channel)
            }

            is Event.Ended -> {
                if (event.generation == loop.current) ended(loop, event.error)
            }

            Event.BluetoothOn -> {
                bluetoothOn(loop)
            }
        }
    }

    /** Bluetooth turned on: a retry that only waits because Bluetooth was off starts now (DiPlay PR #914). */
    private fun bluetoothOn(loop: Loop) {
        val waiting = env.time.elapsedMillis() < loop.retryAt
        if (!loop.started || loop.lastError !is CarPlayError.BluetoothUnavailable || !waiting) return
        env.log.info("carplay.bluetooth.on_retry", "attempt" to loop.failures + 1)
        loop.job?.cancel()
        loop.retryAt = 0L
        launchAttempt(loop, pause = 0)
    }

    private fun begin(loop: Loop) {
        loop.started = true
        loop.failures = 0
        loop.lastError = null
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
        channel: Int,
    ) {
        loop.failures = 0
        loop.lastError = null
        stateFlow.value = CarPlayState.Connected(phone, env.time.clock.instant(), channel)
        env.log.info("carplay.connected", "phone" to phone, "channel" to channel)
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
        loop.lastError = error
        val pause = pauseAfter(error, loop.failures)
        loop.retryAt = env.time.elapsedMillis() + pause
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
            val config = config()
            env.log.info(
                "carplay.config",
                "width" to config.widthPixels,
                "height" to config.heightPixels,
                "width_mm" to config.widthMillimetres,
                "height_mm" to config.heightMillimetres,
                "fps" to config.maxFps,
                "view_areas" to config.viewAreas.size,
                "view_area" to config.startViewArea.let { "${it.width}x${it.height}+${it.originX}+${it.originY}" },
            )
            WirelessAttempt(
                ports,
                config,
                env,
                onStep = { inbox.trySend(Event.Step(generation, it)) },
                onConnected = { phone, channel -> inbox.trySend(Event.Connected(generation, phone, channel)) },
                plugins = plugins,
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

    /**
     * 2 s doubling to 30 s (DiPlay HOST:6298); an unanswered dial 1.5 s for the first [QUICK_DIALS] failures in a
     * row (CarLink HOST:6288-6296, 7247-7251: "~5 s per try and often succeeds on the next one; the doubling
     * backoff turned four misses into ~55 s"). ±20 % jitter so a flapping link does not retry in lockstep.
     */
    private fun pauseAfter(
        error: CarPlayError,
        failures: Int,
    ): Long {
        val base =
            if (error is CarPlayError.DialFailed && failures <= QUICK_DIALS) {
                QUICK_DIAL_PAUSE
            } else {
                min(BASE_PAUSE * (1L shl min(failures - 1, MAX_SHIFT)), MAX_PAUSE)
            }
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
            val channel: Int,
        ) : Event

        data class Ended(
            val generation: Int,
            val error: CarPlayError?,
        ) : Event

        data object BluetoothOn : Event
    }

    private companion object {
        const val BASE_PAUSE = 2_000L
        const val MAX_PAUSE = 30_000L
        const val MAX_SHIFT = 4
        const val JITTER = 0.2
        const val QUICK_DIAL_PAUSE = 1_500L
        const val QUICK_DIALS = 8
    }
}

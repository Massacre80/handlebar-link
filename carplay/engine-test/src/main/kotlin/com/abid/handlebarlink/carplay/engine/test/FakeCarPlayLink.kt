package com.abid.handlebarlink.carplay.engine.test

import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test double of [CarPlayLink] for other modules' tests. Tests drive it from two sides:
 * - **Control:** [setState], [setKeepAlive] set what the fake reports.
 * - **Verification:** [commands] records every [start]/[stop] the code under test made.
 */
public class FakeCarPlayLink(
    initial: CarPlayState = CarPlayState.Stopped,
) : CarPlayLink {
    /** A command the code under test sent. */
    public sealed interface Command {
        /** [CarPlayLink.start] was called. */
        public data object Start : Command

        /** [CarPlayLink.stop] was called. */
        public data object Stop : Command
    }

    private val stateFlow = MutableStateFlow(initial)
    private val keepAliveFlow = MutableStateFlow(false)
    private val running = AtomicBoolean(false)
    private val recorded = CopyOnWriteArrayList<Command>()

    /** Every command the code under test has called, in order. */
    public val commands: List<Command> get() = recorded.toList()

    /** Whether [run] is currently suspended. */
    public val isRunning: Boolean get() = running.get()

    /** Sets the state the fake reports. */
    public fun setState(state: CarPlayState) {
        stateFlow.value = state
    }

    /** Sets the keep-alive flag the fake reports. */
    public fun setKeepAlive(keepAlive: Boolean) {
        keepAliveFlow.value = keepAlive
    }

    override val state: StateFlow<CarPlayState> = stateFlow.asStateFlow()

    override val keepAlive: StateFlow<Boolean> = keepAliveFlow.asStateFlow()

    override fun start() {
        recorded += Command.Start
    }

    override fun stop() {
        recorded += Command.Stop
    }

    override suspend fun run() {
        check(running.compareAndSet(false, true)) { "FakeCarPlayLink.run() is already running" }
        try {
            awaitCancellation()
        } finally {
            running.set(false)
        }
    }
}

package com.abid.handlebarlink.carplay.engine.test

import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayKey
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.TouchContact
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test double of [CarPlayLink] for other modules' tests. Tests drive it from two sides:
 * - **Control:** [setState], [setKeepAlive], [setCalls] set what the fake reports.
 * - **Verification:** [commands] records every [start]/[stop] the code under test made, [touches] every [touch],
 *   [controls] every [press] and [requestSiri].
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

    /** A key press or Siri request the code under test sent. */
    public sealed interface Control {
        /** [CarPlayLink.press] was called with [key]. */
        public data class Press(
            val key: CarPlayKey,
        ) : Control

        /** [CarPlayLink.requestSiri] was called. */
        public data object Siri : Control
    }

    private val stateFlow = MutableStateFlow(initial)
    private val keepAliveFlow = MutableStateFlow(false)
    private val running = AtomicBoolean(false)
    private val recorded = CopyOnWriteArrayList<Command>()
    private val touched = CopyOnWriteArrayList<List<TouchContact>>()
    private val controlled = CopyOnWriteArrayList<Control>()
    private val callsFlow = MutableStateFlow<List<CarPlayCall>>(emptyList())

    /** Every command the code under test has called, in order. */
    public val commands: List<Command> get() = recorded.toList()

    /** Every [touch] the code under test made, in order. */
    public val touches: List<List<TouchContact>> get() = touched.toList()

    /** Every [press] and [requestSiri] the code under test made, in order. */
    public val controls: List<Control> get() = controlled.toList()

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

    /** Sets the calls the fake reports. */
    public fun setCalls(calls: List<CarPlayCall>) {
        callsFlow.value = calls.toList()
    }

    override val state: StateFlow<CarPlayState> = stateFlow.asStateFlow()

    override val calls: StateFlow<List<CarPlayCall>> = callsFlow.asStateFlow()

    override val keepAlive: StateFlow<Boolean> = keepAliveFlow.asStateFlow()

    override fun start() {
        recorded += Command.Start
    }

    override fun stop() {
        recorded += Command.Stop
    }

    override fun touch(contacts: List<TouchContact>) {
        touched += contacts.toList()
    }

    override fun press(key: CarPlayKey) {
        controlled += Control.Press(key)
    }

    override fun requestSiri() {
        controlled += Control.Siri
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

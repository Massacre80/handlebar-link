package com.abid.handlebarlink.bike.link.test

import com.abid.handlebarlink.bike.link.BikeLink
import com.abid.handlebarlink.bike.link.ButtonEvent
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [BikeLink] for other modules' tests: the test sets the state and sends presses, and reads back
 * the commands it received. Not thread-safe; use it from one test coroutine.
 */
public class FakeBikeLink(
    initial: LinkState = LinkState.Stopped,
) : BikeLink {
    /** A command the code under test sent. */
    public sealed interface Command {
        /** [BikeLink.start]. */
        public data class Start(
            /** The hint it was called with. */
            val presentHint: Boolean,
        ) : Command

        /** [BikeLink.stop]. */
        public data object Stop : Command

        /** [BikeLink.onPresence]. */
        public data class Presence(
            /** The presence it was called with. */
            val present: Boolean,
        ) : Command
    }

    private val stateFlow = MutableStateFlow(initial)
    private val keepAliveFlow = MutableStateFlow(false)
    private val problemFlow = MutableStateFlow<LinkProblem?>(null)
    private val buttonFlow = MutableSharedFlow<ButtonEvent>(extraBufferCapacity = 16)
    private val received = mutableListOf<Command>()

    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()
    override val keepAlive: StateFlow<Boolean> = keepAliveFlow.asStateFlow()
    override val problem: StateFlow<LinkProblem?> = problemFlow.asStateFlow()
    override val buttons: Flow<ButtonEvent> = buttonFlow.asSharedFlow()

    /** Every command received so far, in order. */
    public val commands: List<Command> get() = received.toList()

    /** Whether [run] is currently running. */
    public var isRunning: Boolean = false
        private set

    /** Sets what the link reports. */
    public fun setState(state: LinkState) {
        stateFlow.value = state
    }

    /** Sets whether the link asks to keep the service alive. */
    public fun setKeepAlive(keepAlive: Boolean) {
        keepAliveFlow.value = keepAlive
    }

    /** Sets the problem the link reports. */
    public fun setProblem(problem: LinkProblem?) {
        problemFlow.value = problem
    }

    /** Delivers a press to collectors of [buttons]; returns false if nobody could receive it. */
    public fun press(event: ButtonEvent): Boolean = buttonFlow.tryEmit(event)

    override fun start(presentHint: Boolean) {
        received += Command.Start(presentHint)
    }

    override fun stop() {
        received += Command.Stop
    }

    override fun onPresence(present: Boolean) {
        received += Command.Presence(present)
    }

    override suspend fun run() {
        check(!isRunning) { "BikeLink.run() is already running" }
        isRunning = true
        try {
            awaitCancellation()
        } finally {
            isRunning = false
        }
    }
}

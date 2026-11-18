package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What an attempt opened, closed in reverse order (DiPlay closeWirelessStack, CarPlayController.kt:3252-3289). */
internal class Cleanup(
    private val log: Logger,
) {
    private val actions = ArrayDeque<Pair<String, suspend () -> Unit>>()

    fun add(
        name: String,
        action: suspend () -> Unit,
    ) {
        actions.addFirst(name to action)
    }

    /**
     * Runs every action, newest first; a failing one is logged and the rest still run. Called under
     * NonCancellable, so a cancellation an action throws is its own timeout, not ours: it is logged like any
     * failure, never left to stop the remaining closes or to replace the attempt's real error.
     */
    @Suppress("TooGenericExceptionCaught") // A broken close must not leave the radio or other sockets open.
    suspend fun closeAll() {
        while (actions.isNotEmpty()) {
            val (name, action) = actions.removeFirst()
            try {
                action()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // Only if called outside NonCancellable: ours, propagate.
                log.error("carplay.cleanup.failed", e, "resource" to name)
            } catch (e: Exception) {
                log.error("carplay.cleanup.failed", e, "resource" to name)
            }
        }
    }
}

package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayKey
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * The rider's key presses and Siri requests on their way to the iPhone (`CarPlayLink.press` and
 * `CarPlayLink.requestSiri`).
 * [press] and [requestSiri] only queue (any thread, never block); the session that holds the event channel [serve]s
 * the queue. Kept apart from the touches so a burst of touches can never push a press out.
 */
internal class ControlInput {
    private sealed interface Request {
        data class Key(
            val key: CarPlayKey,
        ) : Request

        data object Siri : Request
    }

    private val queue = Channel<Request>(CAPACITY, BufferOverflow.DROP_OLDEST)

    fun press(key: CarPlayKey) {
        queue.trySend(Request.Key(key))
    }

    fun requestSiri() {
        queue.trySend(Request.Siri)
    }

    /**
     * Sends each queued request to [events] until cancelled: a key as its report and release, Siri as DiPlay's two
     * `requestSiri` commands. Requests that waited for this event channel are dropped first: a press made before the
     * session (an answer meant for an earlier call) must not land in it.
     */
    suspend fun serve(
        events: EventCommands,
        log: Logger,
    ) {
        var stale = 0
        while (queue.tryReceive().isSuccess) stale++
        if (stale > 0) log.warn("carplay.hid.stale_dropped", "requests" to stale)
        for (request in queue) {
            when (request) {
                is Request.Key -> {
                    val sent = sendAll(events, KeyCommands.press(request.key))
                    log.info("carplay.hid.key", "key" to request.key, "sent" to sent)
                }

                Request.Siri -> {
                    val sent = sendAll(events, KeyCommands.siri())
                    log.info("carplay.siri.requested", "sent" to sent)
                }
            }
        }
    }

    /**
     * Sends every command, also after one failed (DiPlay sends the release whatever became of the press,
     * AirPlaySession.kt:380-388); true when all went out.
     */
    private suspend fun sendAll(
        events: EventCommands,
        commands: List<Plist.Dict>,
    ): Boolean {
        var sent = true
        for (command in commands) {
            if (!events.send(command)) sent = false
        }
        return sent
    }

    private companion object {
        /** Far more than a rider presses while a session is busy; only a stuck session fills it. */
        const val CAPACITY = 16
    }
}

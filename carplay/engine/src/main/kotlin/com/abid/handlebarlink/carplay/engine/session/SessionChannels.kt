package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.protocol.plist.Plist
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * What the session SETUP opens (DiPlay AirPlaySession.kt:706-733, 855-889; spec D §5.1): the timing socket and its
 * responder, the event channel's listener, and the keep-alive socket when the iPhone asks for one. A repeated session
 * SETUP replaces them. Everything is closed by [close] or with the session's scope.
 */
internal class SessionChannels(
    private val context: SessionContext,
) {
    private val opened = mutableListOf<Job>()
    private var events: EventChannel? = null

    /** Commands on the event channel once the iPhone has connected it. */
    val commands = EventCommands { command -> events?.send(command) ?: false }

    /** Opens the channels; returns the reply plist. On a failure, closes what it opened and throws. */
    suspend fun setup(request: Plist.Dict): Plist.Dict {
        close()
        var done = false
        try {
            val reply = LinkedHashMap<String, Plist>()
            val peerTimingPort = request.long("timingPort")?.takeIf { it in 1..MAX_PORT }?.toInt() ?: 0
            reply["timingPort"] = Plist.of(openTiming(peerTimingPort))
            reply["eventPort"] = Plist.of(openEvent())
            if (wantsKeepAlive(request["keepAliveLowPower"])) reply["keepAlivePort"] = Plist.of(openKeepAlive())
            reply["enabledFeatures"] = Plist.of(ENABLED_FEATURES)
            context.log.info(
                "carplay.airplay.session_setup",
                "model" to request.text("model"),
                "os" to request.text("osVersion"),
                "source" to request.text("sourceVersion"),
                "timingPort" to reply["timingPort"],
                "peerTimingPort" to peerTimingPort,
                "eventPort" to reply["eventPort"],
                "keepAlive" to reply.containsKey("keepAlivePort"),
            )
            done = true
            return Plist.Dict(reply)
        } finally {
            if (!done) close()
        }
    }

    /** DiPlay NtpClock.listen and start (AirPlaySession.kt:855-859): answer always, ask only given a peer port. */
    private suspend fun openTiming(peerPort: Int): Int {
        val socket = context.platform.network.bindUdp()
        val job =
            context.launchPart("timing") {
                context.seams.timing.run(socket, context.peer, peerPort)
            }
        track(job) { socket.close() }
        return socket.port
    }

    /** A TCP listener for the one event connection, from the control connection's peer (AirPlaySession.kt:884-951). */
    private suspend fun openEvent(): Int {
        val listener = context.platform.network.listen(context.platform.addresses, 0)
        val job =
            context.launchVital("event channel") {
                val connection = context.acceptFromPeer(listener, "event")
                listener.close()
                context.log.info("carplay.airplay.event_connected", "peer" to connection.remoteAddress)
                try {
                    val channel =
                        EventChannel(connection, context.sharedSecret, context.platform.cipherName, context.log)
                    events = channel
                    coroutineScope {
                        // Touches and keys go out while the channel lasts; its end (always a throw) cancels them.
                        launch { serveTouches(channel) }
                        launch { context.platform.controls.serve(channel, context.log) }
                        channel.run()
                    }
                } finally {
                    events = null
                    connection.close()
                }
            }
        track(job) { listener.close() }
        return listener.port
    }

    private suspend fun serveTouches(channel: EventChannel) {
        val config = context.platform.config
        context.platform.touches.serve(channel, config.widthPixels, config.heightPixels, context.log)
    }

    /** Incoming keep-alive packets are read and dropped (AirPlaySession.kt:861-882). */
    private suspend fun openKeepAlive(): Int {
        val socket = context.platform.network.bindUdp()
        val job =
            context.launchPart("keep-alive") {
                while (socket.receive() != null) {
                    // Only read: the iPhone's keep-alive needs no answer.
                }
            }
        track(job) { socket.close() }
        return socket.port
    }

    private fun track(
        job: Job,
        release: () -> Unit,
    ) {
        opened += job
        job.invokeOnCompletion { release() }
    }

    fun close() {
        opened.forEach { it.cancel() }
        opened.clear()
        events = null
    }

    private fun wantsKeepAlive(value: Plist?): Boolean =
        value == Plist.Bool(true) || (value as? Plist.Integer)?.bits == 1L

    private companion object {
        const val MAX_PORT = 65_535L

        /**
         * DiPlay's `setupEnabledFeatures` (AirPlaySession.kt:1054-1064) for this receiver: no HEVC, no cluster, no
         * corner masks, no video in car, no buffered audio, so only the two it always enables.
         */
        val ENABLED_FEATURES = listOf("iAPChannel", "viewAreas")
    }
}

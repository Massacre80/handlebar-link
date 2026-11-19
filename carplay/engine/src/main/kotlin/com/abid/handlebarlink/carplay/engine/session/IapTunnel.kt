package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.ByteStream
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.TcpListener
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Link
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2LinkParameters
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.carplay.protocol.stream.IapTunnelException
import com.abid.handlebarlink.carplay.protocol.stream.IapTunnelReader
import com.abid.handlebarlink.carplay.protocol.stream.StreamKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.AEADBadTagException

/**
 * The iAP2 tunnel one type-130 stream SETUP opens (DiPlay CarPlayMediaEngine.kt:379-443, 474-487 and
 * CarPlayController.kt:1888-1957; spec D §1.5): once the iPhone connects to the data port from the control
 * connection's address (within [PEER_CONNECT_TIMEOUT], AirPlayIapTunnelStream.kt:59-61), an [Iap2Channel] with a link
 * that initiates and advertises zero acknowledgements runs over it, and the same [BootstrapControl] as on Bluetooth
 * identifies, authenticates, subscribes and then answers the iPhone; its CallStateUpdates go to the same calls.
 * Ready after the subscriptions, as DiPlay's `onReady`; then [WirelessHandoff] may close Bluetooth.
 *
 * Runs in the AirPlay session's scope as a part whose end is only logged; its job's completion reports the end to
 * [WirelessHandoff], which decides what it means for the attempt.
 */
internal class IapTunnel(
    private val context: SessionContext,
    private val commands: EventCommands,
    private val setup: BootstrapSetup,
    private val handoff: WirelessHandoff,
    /** This tunnel's token from [WirelessHandoff.tunnelStarting]. */
    private val token: Int,
) {
    /** A launched tunnel: its job, its data listener and the SETUP reply. */
    class Started(
        val job: Job,
        val listener: TcpListener,
        val reply: Plist.Dict,
    )

    /** Why [run] ended, for the hand-over's log; set before it throws. */
    var endReason: String = "not started"
        private set

    /** Serves the tunnel on [listener] with the stream's [readKey] until it ends; always ends by throwing. */
    suspend fun run(
        listener: TcpListener,
        readKey: ByteArray,
    ) {
        endReason = "failed"
        var stream: IapTunnelStream? = null
        try {
            val first = firstConnection(listener)
            context.log.info("carplay.iap2.tunnel.connected", "peer" to first.remoteAddress)
            val tunnel = IapTunnelStream(first, readKey, context, listener, commands).also { stream = it }
            serve(tunnel)
        } catch (e: CancellationException) {
            endReason = "cancelled"
            throw e
        } catch (e: CarPlayException) {
            endReason = e.error.toString()
            throw e
        } finally {
            stream?.close()
        }
    }

    private suspend fun firstConnection(listener: TcpListener): TcpConnection =
        withTimeoutOrNull(PEER_CONNECT_TIMEOUT) { context.acceptFromPeer(listener, CHANNEL) }
            ?: throw CarPlayException(CarPlayError.LinkFailed("iPhone did not connect to the iAP2 tunnel"))

    private suspend fun serve(stream: IapTunnelStream): Nothing =
        coroutineScope {
            val link = Iap2Link(Iap2LinkParameters.TUNNEL, initiate = true)
            val channel = Iap2Channel(stream, context.platform.time, context.log, Iap2Channel.TUNNEL, link)
            launch { channel.run() }
            val control =
                BootstrapControl(channel, setup, context.platform.time, context.log, NO_MILESTONES, handoff.calls)
            channel.connected.await()
            control.identify()
            control.authenticate()
            handoff.tunnelReady(token)
            control.serve()
        }

    companion object {
        private const val CHANNEL = "iap2 tunnel"

        /** DiPlay's PEER_CONNECT_TIMEOUT_MILLIS (AirPlayIapTunnelStream.kt:169). */
        const val PEER_CONNECT_TIMEOUT = 15_000L

        /** The tunnel's streamID in the SETUP reply (CarPlayMediaEngine.kt:437). */
        private const val STREAM_ID = 1L

        /** StartSession and Wi-Fi answers over the tunnel feed no link watch (DiPlay passes no hooks, :1918-1931). */
        private val NO_MILESTONES =
            object : BootstrapMilestones {
                override fun wifiOffered() = Unit

                override fun startSessionSent() = Unit
            }

        /**
         * Opens the tunnel a SETUP asked for (the bootstrap's setup must exist): its listener, its job, and
         * `{type 130, streamID 1, dataPort[, streamConnectionID as received]}` (CarPlayMediaEngine.kt:396-442). The new
         * tunnel's token is taken before [replaceOld] cancels the open one, so the old one's end is reported as stale;
         * every end is reported from the job's completion, which also runs for a job cancelled before it started.
         */
        suspend fun start(
            context: SessionContext,
            commands: EventCommands,
            stream: Plist.Dict,
            seed: Plist.Integer,
            replaceOld: () -> Unit,
        ): Started {
            val handoff = context.platform.handoff
            val setup = checkNotNull(handoff.setup) { "no bootstrap setup" }
            val token = handoff.tunnelStarting()
            replaceOld()
            var started: Started? = null
            try {
                val tunnel = IapTunnel(context, commands, setup, handoff, token)
                val key = StreamKeys.output(context.sharedSecret, seed.bits)
                val listener = context.platform.network.listen(context.platform.addresses, 0)
                val job = context.launchPart(CHANNEL) { tunnel.run(listener, key) }
                job.invokeOnCompletion {
                    handoff.tunnelEnded(token, tunnel.endReason, sessionEnding = !context.scope.isActive)
                }
                val id = stream["streamConnectionID"]
                val reply = linkedMapOf("type" to Plist.of(MediaStreams.DATA), "streamID" to Plist.of(STREAM_ID))
                reply["dataPort"] = Plist.of(listener.port)
                if (id != null) reply["streamConnectionID"] = id
                context.log.info(
                    "carplay.iap2.tunnel.setup",
                    "dataPort" to listener.port,
                    "streamConnectionID" to (id != null),
                )
                return Started(job, listener, Plist.Dict(reply)).also { started = it }
            } finally {
                if (started == null) handoff.tunnelEnded(token, "not opened", sessionEnding = false)
            }
        }
    }
}

/**
 * The tunnel as the iAP2 link's byte stream (DiPlay AirPlayIapTunnelStream.kt and IapTunnel.kt): reads come from the
 * encrypted data connection ([IapTunnelReader]); writes go out as `POST /command {type: "iAPSendMessage", params:
 * {data}}` on the event channel (AirPlaySession.kt:395-427), waiting up to [EVENT_READY_TIMEOUT] for it.
 *
 * The iPhone closing the data connection does not end the tunnel: DiPlay accepts its next connection from the same
 * address and starts the counter again (IapTunnel.kt:108-135, 147-150; AirPlayIapTunnelStreamTest). A frame that does
 * not authenticate or an impossible record does (IapTunnel.kt:161-166).
 */
internal class IapTunnelStream(
    private var connection: TcpConnection,
    private val readKey: ByteArray,
    private val context: SessionContext,
    private val listener: TcpListener,
    private val commands: EventCommands,
) : ByteStream {
    private var reader = IapTunnelReader(readKey, context.platform.cipherName)
    private val chunk = ByteArray(READ_CHUNK)
    private var pending = ByteArray(0)
    private var closed = false

    override suspend fun read(buffer: ByteArray): Int {
        while (pending.isEmpty() && !closed) fill()
        if (pending.isEmpty()) return -1
        val count = minOf(buffer.size, pending.size)
        pending.copyInto(buffer, 0, 0, count)
        pending = pending.copyOfRange(count, pending.size)
        return count
    }

    /** One read from the data connection; at its end, the iPhone's next connection. */
    private suspend fun fill() {
        val count = connection.read(chunk)
        when {
            count >= 0 -> {
                pending += decode(count)
            }

            !closed -> {
                context.log.info("carplay.iap2.tunnel.peer_eof")
                connection.close()
                connection = context.acceptFromPeer(listener, "iap2 tunnel")
                reader = IapTunnelReader(readKey, context.platform.cipherName)
                context.log.info("carplay.iap2.tunnel.connected", "peer" to connection.remoteAddress)
            }

            else -> {}
        }
    }

    private fun decode(count: Int): ByteArray =
        try {
            reader.receive(chunk, 0, count).fold(ByteArray(0)) { all, body -> all + body }
        } catch (e: AEADBadTagException) {
            throw CarPlayException(CarPlayError.LinkFailed("iAP2 tunnel frame failed authentication"), e)
        } catch (e: IapTunnelException) {
            throw CarPlayException(CarPlayError.LinkFailed(e.message ?: "bad iAP2 tunnel record"), e)
        }

    override suspend fun write(bytes: ByteArray) {
        val command = Plist.dict("type" to SEND_MESSAGE, "params" to Plist.dict("data" to bytes))
        withTimeoutOrNull(EVENT_READY_TIMEOUT) {
            while (!commands.send(command)) delay(EVENT_READY_POLL)
            true
        } ?: throw CarPlayException(CarPlayError.LinkFailed("AirPlay event channel did not take an iAP2 message"))
    }

    override fun close() {
        closed = true
        connection.close()
    }

    private companion object {
        /** DiPlay's command type (AirPlaySession.kt:398). */
        const val SEND_MESSAGE = "iAPSendMessage"

        /** DiPlay's EVENT_READY_TIMEOUT_MILLIS (AirPlayIapTunnelStream.kt:170). */
        const val EVENT_READY_TIMEOUT = 10_000L

        /** DiPlay's EVENT_READY_POLL_MILLIS (AirPlaySession.kt:1027). */
        const val EVENT_READY_POLL = 25L

        /** DiPlay's READ_CHUNK_BYTES (IapTunnel.kt:223). */
        const val READ_CHUNK = 16 * 1024
    }
}

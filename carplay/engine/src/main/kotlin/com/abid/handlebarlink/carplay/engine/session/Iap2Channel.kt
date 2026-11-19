package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.ByteStream
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2ControlStream
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Link
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2LinkEvent
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2LinkException
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2LinkParameters
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Message
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2ProtocolException
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the pure [Iap2Link] over [stream] (the RFCOMM socket, or the AirPlay iAP2 tunnel with a link that initiates,
 * DiPlay `Iap2LinkChannel.openTunnel`): a read loop feeds received bytes, a timer loop runs resends and
 * acknowledgements at the link's deadlines, and every output is written in the order the link produced it.
 * Control-session messages come out of [receive]. All link access happens on the engine's single-threaded dispatcher.
 * The same code serves both links, so the tunnel speaks the iAP2 that the Bluetooth bootstrap does.
 */
internal class Iap2Channel(
    private val stream: ByteStream,
    private val time: TimeProvider,
    private val log: Logger,
    /** Which link this is, in logs: "bluetooth" or "tunnel". */
    val name: String = BLUETOOTH,
    private val link: Iap2Link = Iap2Link(),
) {
    private val control = Iap2ControlStream()
    private val incoming = Channel<Iap2Message>(Channel.UNLIMITED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()

    /** Completes with the iPhone's link parameters once the handshake is done. */
    val connected = CompletableDeferred<Iap2LinkParameters>()

    /**
     * Runs until the link fails or the iPhone closes the stream; always throws (a [CarPlayException], or
     * cancellation). Closes [receive] with the same failure.
     */
    @Suppress("TooGenericExceptionCaught") // Every failure must reach receive()'s waiters; it is rethrown.
    suspend fun run(): Nothing {
        var failure: Throwable? = null
        try {
            coroutineScope {
                link.start(time.elapsedMillis())
                flush()
                launch { timers() }
                reads()
            }
        } catch (e: Iap2LinkException) {
            failure = CarPlayException(CarPlayError.LinkFailed(e.message ?: "iAP2 link failed"), e)
        } catch (e: Iap2ProtocolException) {
            failure = CarPlayException(CarPlayError.LinkFailed(e.message ?: "bad iAP2 message"), e)
        } catch (e: Throwable) {
            failure = e
        } finally {
            incoming.close(failure)
            connected.completeExceptionally(failure ?: IllegalStateException("iAP2 channel ended"))
        }
        throw checkNotNull(failure)
    }

    /** The next control-session message; throws the channel's failure once it has ended. */
    suspend fun receive(): Iap2Message = incoming.receive()

    /** Sends [message] on the control session, split to the iPhone's frame size. */
    suspend fun send(message: Iap2Message) {
        val maxPayload = checkNotNull(link.maxPayloadBytes) { "iAP2 link not connected" }
        val now = time.elapsedMillis()
        try {
            Iap2ControlStream.chunks(message, maxPayload).forEach {
                link.send(Iap2LinkParameters.CONTROL_SESSION, it, now)
            }
        } catch (e: Iap2LinkException) {
            throw CarPlayException(CarPlayError.LinkFailed(e.message ?: "iAP2 send failed"), e)
        }
        log.debug("carplay.iap2.tx", "link" to name, "id" to "0x%04X".format(message.id))
        flush()
    }

    private suspend fun reads(): Nothing {
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) throw CarPlayException(CarPlayError.LinkFailed("iPhone closed the $name link"))
            link.receive(buffer, 0, count, time.elapsedMillis())
            flush()
        }
    }

    private suspend fun timers(): Nothing {
        while (true) {
            val deadline = link.nextDeadline()
            if (deadline == null) {
                wake.receive()
            } else {
                withTimeoutOrNull((deadline - time.elapsedMillis()).coerceAtLeast(0)) { wake.receive() }
            }
            link.onTimer(time.elapsedMillis())
            // Not wakeTimers: the loop re-reads the deadline anyway, and waking itself would spin forever.
            flush(wakeTimers = false)
        }
    }

    /** Writes what the link produced and hands out its events; then lets the timer loop re-plan. */
    private suspend fun flush(wakeTimers: Boolean = true) {
        writeLock.withLock {
            val bytes = link.drainOutput()
            if (bytes.isNotEmpty()) stream.write(bytes)
        }
        link.drainEvents().forEach { deliver(it) }
        if (wakeTimers) wake.trySend(Unit)
    }

    private suspend fun deliver(event: Iap2LinkEvent) {
        when (event) {
            is Iap2LinkEvent.Connected -> {
                log.info("carplay.iap2.connected", "link" to name, "maxPayload" to event.peer.maxPayloadBytes)
                connected.complete(event.peer)
            }

            is Iap2LinkEvent.Message -> {
                if (event.sessionId == Iap2LinkParameters.CONTROL_SESSION) {
                    control.receive(event.payload).forEach { deliverControl(it) }
                } else {
                    log.debug("carplay.iap2.session_ignored", "session" to event.sessionId)
                }
            }
        }
    }

    private suspend fun deliverControl(message: Iap2Message) {
        // Never fatal, as DiPlay (PR #1297); the handler sees it without parameters.
        if (message.malformed) log.warn("carplay.iap2.malformed", "id" to "0x%04X".format(message.id))
        incoming.send(message)
    }

    companion object {
        const val BLUETOOTH = "bluetooth"
        const val TUNNEL = "tunnel"

        /** DiPlay's read chunk (BluetoothRfcommDuplexStream.kt READ_CHUNK_BYTES). */
        private const val READ_CHUNK = 8_192
    }
}

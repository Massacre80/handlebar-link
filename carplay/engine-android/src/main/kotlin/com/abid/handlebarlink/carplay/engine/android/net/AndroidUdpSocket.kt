package com.abid.handlebarlink.carplay.engine.android.net

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.daemonThreads
import com.abid.handlebarlink.carplay.engine.spi.Datagram
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A [UdpSocket] over a blocking [DatagramSocket], in `ThreadedByteStream`'s style: receives and sends each run on the
 * socket's own thread. Cancelling a receive closes the socket (the only way to abort a blocked receive); cancelling a
 * send only stops waiting for it. After [close] a receive returns null and a send fails. Any exception from the socket
 * is the port's error, never an uncaught one on a bare thread.
 */
internal class AndroidUdpSocket(
    private val socket: DatagramSocket,
    /** The receiving thread's `Process` priority (an audio data socket's, DiPlay AudioStream.kt:92); null keeps it. */
    receivePriority: Int? = null,
) : UdpSocket {
    private val closed = AtomicBoolean(false)
    private val receiver: ExecutorService =
        Executors.newSingleThreadExecutor(daemonThreads("carplay-udp-rx", receivePriority))
    private val sender: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("carplay-udp-tx"))
    private val packet = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)

    /** The last sender and its text, so a steady peer costs no String per datagram (touched on [receiver] only). */
    private var lastSender: InetAddress? = null
    private var lastSenderText = ""

    /** The last destination as given and resolved, so a steady peer is resolved once (touched on [sender] only). */
    private var lastDestinationText: String? = null
    private var lastDestination: InetSocketAddress? = null

    override val port: Int = socket.localPort

    override suspend fun receive(): Datagram? =
        onThread(receiver, closeOnCancel = true) {
            packet.setLength(MAX_DATAGRAM)
            socket.receive(packet)
            Datagram(
                packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                senderText(packet.address),
                packet.port,
            )
        }

    private fun senderText(address: InetAddress?): String {
        if (address != lastSender) {
            lastSender = address
            lastSenderText = address?.hostAddress.orEmpty()
        }
        return lastSenderText
    }

    override suspend fun send(
        bytes: ByteArray,
        address: String,
        port: Int,
    ) {
        onThread(sender, closeOnCancel = false) {
            socket.send(DatagramPacket(bytes, bytes.size, destination(address, port)))
        } ?: throw CarPlayException(CarPlayError.NetworkFailed("send on a closed UDP socket"))
    }

    /** Numeric literals only (never a DNS lookup); a scoped link-local address keeps its interface. */
    private fun destination(
        address: String,
        port: Int,
    ): InetSocketAddress {
        val last = lastDestination
        if (last != null && last.port == port && lastDestinationText == address) return last
        return InetSocketAddress(InetAddress.getByName(address), port).also {
            lastDestination = it
            lastDestinationText = address
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket.close()
        receiver.shutdown()
        sender.shutdown()
    }

    /**
     * Runs [block] on [executor]; null when the socket is (or gets) closed. [closeOnCancel]: a cancelled wait closes
     * the socket (a blocked receive); otherwise the block still runs and only its result is dropped.
     */
    @Suppress("TooGenericExceptionCaught") // On a bare thread an escaping exception would kill the whole process.
    private suspend fun <T : Any> onThread(
        executor: ExecutorService,
        closeOnCancel: Boolean,
        block: () -> T,
    ): T? {
        if (closed.get()) return null
        return suspendCancellableCoroutine { continuation ->
            if (closeOnCancel) continuation.invokeOnCancellation { close() }
            try {
                executor.execute {
                    try {
                        continuation.resume(block())
                    } catch (e: IOException) {
                        if (closed.get()) {
                            continuation.resume(null)
                        } else {
                            continuation.resumeWithException(
                                CarPlayException(CarPlayError.NetworkFailed(e.toString()), e),
                            )
                        }
                    } catch (e: RuntimeException) {
                        continuation.resumeWithException(CarPlayException(CarPlayError.NetworkFailed(e.toString()), e))
                    }
                }
            } catch (_: RejectedExecutionException) {
                continuation.resume(null) // Closed between the check and the hand-off.
            }
        }
    }

    private companion object {
        /** The largest UDP payload. */
        const val MAX_DATAGRAM = 65_535
    }
}

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
 * socket's own thread, and cancelling either closes the socket (the only way to abort a blocked receive). After
 * [close] a receive returns null and a send fails.
 */
internal class AndroidUdpSocket(
    private val socket: DatagramSocket,
) : UdpSocket {
    private val closed = AtomicBoolean(false)
    private val receiver: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("carplay-udp-rx"))
    private val sender: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("carplay-udp-tx"))
    private val buffer = ByteArray(MAX_DATAGRAM)

    override val port: Int = socket.localPort

    override suspend fun receive(): Datagram? =
        onThread(receiver) {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            Datagram(
                packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                packet.address.hostAddress.orEmpty(),
                packet.port,
            )
        }

    override suspend fun send(
        bytes: ByteArray,
        address: String,
        port: Int,
    ) {
        onThread(sender) {
            // Numeric literals only (never a DNS lookup); a scoped link-local address keeps its interface.
            socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(InetAddress.getByName(address), port)))
        } ?: throw CarPlayException(CarPlayError.NetworkFailed("send on a closed UDP socket"))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket.close()
        receiver.shutdown()
        sender.shutdown()
    }

    /** Runs [block] on [executor]; null when the socket is (or gets) closed. */
    private suspend fun <T : Any> onThread(
        executor: ExecutorService,
        block: () -> T,
    ): T? {
        if (closed.get()) return null
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
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

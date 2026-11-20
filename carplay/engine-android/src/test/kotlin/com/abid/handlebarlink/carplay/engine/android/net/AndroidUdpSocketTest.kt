package com.abid.handlebarlink.carplay.engine.android.net

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** Real loopback sockets on real threads, so every test runs under a wall-clock limit (docs/LESSONS.md 49). */
class AndroidUdpSocketTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private fun bound() = AndroidUdpSocket(DatagramSocket(InetSocketAddress(loopback, 0)))

    @Test
    fun `a datagram arrives whole with its sender, and a reply goes back to it`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val udp = bound()
                DatagramSocket(InetSocketAddress(loopback, 0)).use { peer ->
                    peer.send(DatagramPacket(ByteArray(3) { it.toByte() }, 3, InetSocketAddress(loopback, udp.port)))
                    val received = checkNotNull(udp.receive())
                    assertThat(received.bytes.toList()).isEqualTo(listOf<Byte>(0, 1, 2))
                    assertThat(received.port).isEqualTo(peer.localPort)

                    udp.send(byteArrayOf(9), received.address, received.port)
                    val reply = DatagramPacket(ByteArray(8), 8)
                    peer.soTimeout = LIMIT.toInt()
                    peer.receive(reply)
                    assertThat(reply.length to reply.data[0]).isEqualTo(1 to 9.toByte())
                }
                udp.close()
            }
        }

    @Test
    fun `cancelling a blocked receive closes the socket, and then receive is null and send fails`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val udp = bound()
                val waiting = launch(start = CoroutineStart.UNDISPATCHED) { udp.receive() }
                waiting.cancelAndJoin()

                assertThat(udp.receive()).isNull()
                assertFailure { udp.send(byteArrayOf(1), "127.0.0.1", 9) }.isInstanceOf<CarPlayException>()
            }
        }

    @Test
    fun `closing from another thread ends a pending receive with null`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val udp = bound()
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { udp.receive() }
                udp.close()
                assertThat(waiting.await()).isNull()
                assertThat(waiting.isCompleted).isTrue()
            }
        }

    @Test
    fun `a cancelled send leaves the socket open for its receiver`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val udp = bound()
                DatagramSocket(InetSocketAddress(loopback, 0)).use { peer ->
                    val sending =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            udp.send(byteArrayOf(5), "127.0.0.1", peer.localPort)
                        }
                    sending.cancelAndJoin()
                    peer.send(DatagramPacket(byteArrayOf(7), 1, InetSocketAddress(loopback, udp.port)))

                    assertThat(checkNotNull(udp.receive()).bytes.toList()).isEqualTo(listOf<Byte>(7))
                    // The cancelled datagram still went out.
                    val sent = DatagramPacket(ByteArray(8), 8)
                    peer.soTimeout = LIMIT.toInt()
                    peer.receive(sent)
                    assertThat(sent.data[0]).isEqualTo(5.toByte())
                }
                udp.close()
            }
        }

    @Test
    fun `an unchecked exception from the socket fails the call instead of killing its thread`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val udp = bound()
                // An out-of-range port makes InetSocketAddress throw IllegalArgumentException on the sending thread.
                assertFailure { udp.send(byteArrayOf(1), "127.0.0.1", 70_000) }.isInstanceOf<CarPlayException>()
                udp.close()
            }
        }

    private companion object {
        const val LIMIT = 5_000L
    }
}

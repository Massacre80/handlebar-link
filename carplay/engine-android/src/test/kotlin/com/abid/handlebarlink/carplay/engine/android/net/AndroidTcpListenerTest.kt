package com.abid.handlebarlink.carplay.engine.android.net

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.quietLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Real loopback sockets on real threads, so every test runs under a wall-clock limit (docs/LESSONS.md 49). */
class AndroidTcpListenerTest {
    private val loopback = InetAddress.getLoopbackAddress()

    /** A server whose first [failures] accepts fail like a reset pending connection (accept(2) ECONNABORTED). */
    private class FlakyServer(
        failures: Int,
    ) : ServerSocket() {
        private val left = AtomicInteger(failures)

        override fun accept(): Socket {
            if (left.getAndDecrement() > 0) throw IOException("Software caused connection abort")
            return super.accept()
        }
    }

    private fun server(failures: Int = 0) = FlakyServer(failures).apply { bind(InetSocketAddress(loopback, 0)) }

    private fun connect(server: ServerSocket) = Socket(loopback, server.localPort).apply { soTimeout = LIMIT.toInt() }

    @Test
    fun `a failed accept on one address is retried there, and the connection after it is accepted`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val flaky = server(failures = 3)
                val listener = AndroidTcpListener(listOf(flaky, server()), 0, quietLogger(), retryPause = {})
                connect(flaky).use {
                    val accepted = listener.accept()
                    assertThat(accepted.remoteAddress).isEqualTo(loopback.hostAddress)
                    accepted.close()
                }
                listener.close()
            }
        }

    @Test
    fun `an address that keeps failing stops alone, the other addresses still accept`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val broken = server(failures = Int.MAX_VALUE)
                val good = server()
                val listener = AndroidTcpListener(listOf(broken, good), 0, quietLogger(), retryPause = {})
                Thread.sleep(SETTLE_MILLIS) // The broken address gives up meanwhile.
                connect(good).use {
                    listener.accept().close()
                }
                listener.close()
            }
        }

    @Test
    fun `the listener fails only once every address has stopped`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val listener =
                    AndroidTcpListener(
                        listOf(server(failures = Int.MAX_VALUE), server(failures = Int.MAX_VALUE)),
                        0,
                        quietLogger(),
                        retryPause = {},
                    )
                assertFailure { listener.accept() }.isInstanceOf<CarPlayException>()
                listener.close()
            }
        }

    /** A dispatcher that runs nothing until the test says so. */
    private class HeldDispatcher : CoroutineDispatcher() {
        val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue += block
        }

        fun runAll() {
            while (true) queue.poll()?.run() ?: return
        }
    }

    @Test
    fun `a connection taken by an accept that was cancelled meanwhile is closed, not leaked`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val server = server()
                val listener = AndroidTcpListener(listOf(server), 0, quietLogger())
                val held = HeldDispatcher()
                val accepting = launch(held) { listener.accept() }
                held.runAll() // accept() now waits on the queue.

                connect(server).use { client ->
                    // The accept thread hands the socket over: the waiting accept is resumed (queued on [held]).
                    while (held.queue.isEmpty()) Thread.sleep(1)
                    accepting.cancel()
                    held.runAll()

                    // Prompt cancellation dropped the socket; it was closed, so the iPhone's side sees the end.
                    assertThat(client.getInputStream().read()).isEqualTo(-1)
                }
                listener.close()
            }
        }

    @Test
    fun `a resource opened while its caller was cancelled is closed`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                val closed = mutableListOf<String>()
                var caller: Job? = null
                val job =
                    launch {
                        caller = currentCoroutineContext()[Job]
                        openOnIo<String>(io, { closed.add(it) }) {
                            caller?.cancel() // The caller goes away while the socket is being bound.
                            "listener"
                        }
                    }
                job.join()

                assertThat(job.isCancelled).isTrue()
                assertThat(closed.toList()).isEqualTo(listOf("listener"))
                io.close()
            }
        }

    @Test
    fun `a socket whose bind fails is closed`() {
        var closed = false
        val socket = Closeable { closed = true }

        assertFailure { boundOrClosed(socket) { throw IOException("Address already in use") } }
            .isInstanceOf<IOException>()
        assertThat(closed).isTrue()
    }

    @Test
    fun `a tuning failure that is not an IOException is logged, never thrown on the accept thread`() {
        ServerSocket(0, 1, loopback).use { server ->
            Socket(loopback, server.localPort).use { client ->
                // On the JVM, ParcelFileDescriptor.fromSocket is an unmocked stub that throws a RuntimeException.
                TcpTuning.configure(client, quietLogger())
                assertThat(client.tcpNoDelay).isTrue()
            }
        }
    }

    private companion object {
        const val LIMIT = 5_000L
        const val SETTLE_MILLIS = 200L
    }
}

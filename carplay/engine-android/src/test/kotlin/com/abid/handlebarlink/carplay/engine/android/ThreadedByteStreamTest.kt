package com.abid.handlebarlink.carplay.engine.android

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real threads, so every test runs under a wall-clock limit (docs/LESSONS.md 49). */
class ThreadedByteStreamTest {
    /** A socket whose blocking calls wait until it is closed, as a real one does. */
    private class BlockedSocket(
        private val failWhileOpen: Boolean = false,
    ) : BlockingIo {
        val closed = CountDownLatch(1)
        val blocked = CountDownLatch(1)

        override fun read(buffer: ByteArray): Int {
            blocked.countDown()
            if (failWhileOpen) throw IOException("connection reset")
            closed.await()
            throw IOException("socket closed") // What Android 12-16 RFCOMM and TCP sockets do on close.
        }

        override fun write(bytes: ByteArray) {
            read(bytes)
        }

        override fun close() {
            closed.countDown()
        }
    }

    private fun stream(socket: BlockingIo) = ThreadedByteStream(socket, "test") { CarPlayError.LinkFailed(it) }

    @Test
    fun `cancelling a blocked read closes the socket`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val socket = BlockedSocket()
                val stream = stream(socket)
                val reader = launch(start = CoroutineStart.UNDISPATCHED) { stream.read(ByteArray(8)) }
                assertThat(socket.blocked.await(LIMIT, TimeUnit.MILLISECONDS)).isTrue()
                reader.cancelAndJoin()
                assertThat(socket.closed.await(LIMIT, TimeUnit.MILLISECONDS)).isTrue()
                assertThat(stream.isClosed).isTrue()
            }
        }

    @Test
    fun `closing the stream ends a blocked read with -1`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val socket = BlockedSocket()
                val stream = stream(socket)
                val read = async(start = CoroutineStart.UNDISPATCHED) { stream.read(ByteArray(8)) }
                assertThat(socket.blocked.await(LIMIT, TimeUnit.MILLISECONDS)).isTrue()
                stream.close()
                assertThat(read.await()).isEqualTo(-1)
                assertThat(stream.read(ByteArray(8))).isEqualTo(-1)
            }
        }

    @Test
    fun `a socket error while open is the port's error`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val stream = stream(BlockedSocket(failWhileOpen = true))
                assertFailure { stream.read(ByteArray(8)) }
                    .isInstanceOf<CarPlayException>()
                    .prop(CarPlayException::error)
                    .isInstanceOf<CarPlayError.LinkFailed>()
            }
        }

    @Test
    fun `writing to a closed stream fails`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val stream = stream(BlockedSocket())
                stream.close()
                assertFailure { stream.write(byteArrayOf(1)) }.isInstanceOf<CarPlayException>()
            }
        }

    /** A socket whose first write blocks until [release]; every write is recorded whole, in order. */
    private class SlowWrites : BlockingIo {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = java.util.concurrent.CopyOnWriteArrayList<List<Byte>>()
        val closed = CountDownLatch(1)

        override fun read(buffer: ByteArray): Int {
            closed.await()
            return -1
        }

        override fun write(bytes: ByteArray) {
            writing.countDown()
            release.await()
            written += bytes.toList()
        }

        override fun close() {
            closed.countDown()
        }
    }

    @Test
    fun `cancelling a blocked write keeps the stream open for the other writers, and the frame still goes out whole`():
        Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val socket = SlowWrites()
                val stream = stream(socket)
                val first = launch(start = CoroutineStart.UNDISPATCHED) { stream.write(byteArrayOf(1, 2, 3)) }
                assertThat(socket.writing.await(LIMIT, TimeUnit.MILLISECONDS)).isTrue()

                first.cancelAndJoin()
                val second = async(start = CoroutineStart.UNDISPATCHED) { stream.write(byteArrayOf(4, 5)) }
                socket.release.countDown()
                second.await()

                assertThat(stream.isClosed).isFalse()
                assertThat(socket.closed.count).isEqualTo(1L)
                assertThat(socket.written.toList()).isEqualTo(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5)))
                stream.close()
            }
        }

    @Test
    fun `an unchecked exception from the socket fails the read instead of killing its thread`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val socket =
                    object : BlockingIo {
                        override fun read(buffer: ByteArray): Int = error("socket bug")

                        override fun write(bytes: ByteArray) = Unit

                        override fun close() = Unit
                    }
                assertFailure { stream(socket).read(ByteArray(8)) }
                    .isInstanceOf<CarPlayException>()
                    .prop(CarPlayException::error)
                    .isInstanceOf<CarPlayError.LinkFailed>()
            }
        }

    @Test
    fun `cancelling a blocked connect closes the socket and nothing stays open`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val socket = BlockedSocket()
                val dial =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        connectOnOwnThread(
                            "test-dial",
                            socket,
                            connect = { it.read(ByteArray(1)) },
                            close = { it.close() },
                            failure = { CarPlayException(CarPlayError.DialFailed(it.toString()), it) },
                        )
                    }
                assertThat(socket.blocked.await(LIMIT, TimeUnit.MILLISECONDS)).isTrue()
                dial.cancelAndJoin()
                assertThat(socket.closed.await(LIMIT, TimeUnit.MILLISECONDS)).isTrue()
            }
        }

    @Test
    fun `a failed connect closes the socket and reports DialFailed`(): Unit =
        runBlocking {
            withTimeout(LIMIT) {
                val socket = BlockedSocket(failWhileOpen = true)
                assertFailure {
                    connectOnOwnThread(
                        "test-dial",
                        socket,
                        connect = { it.read(ByteArray(1)) },
                        close = { it.close() },
                        failure = { CarPlayException(CarPlayError.DialFailed(it.toString()), it) },
                    )
                }.isInstanceOf<CarPlayException>()
                    .prop(CarPlayException::error)
                    .isInstanceOf<CarPlayError.DialFailed>()
                assertThat(socket.closed.await(LIMIT, TimeUnit.MILLISECONDS)).isTrue()
            }
        }

    private companion object {
        const val LIMIT = 5_000L
    }
}

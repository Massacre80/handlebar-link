package com.abid.handlebarlink.carplay.engine.android

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.ByteStream
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A blocking socket (RFCOMM or TCP), the seam [ThreadedByteStream] runs on its own threads. */
internal interface BlockingIo {
    /** Blocks until bytes arrive; -1 at the end of the stream. */
    fun read(buffer: ByteArray): Int

    /** Blocks until [bytes] are written. */
    fun write(bytes: ByteArray)

    /** Closes the socket, which aborts a blocked [read] or [write] on another thread. */
    fun close()
}

/**
 * A [ByteStream] over a blocking socket: reads and writes each run on the stream's own thread (never the engine's
 * dispatcher), and cancelling either closes the socket, the only way to abort a blocked socket call (Android
 * "Connect Bluetooth devices": `close()` from another thread). After [close], a read returns -1 (what Android 17
 * RFCOMM sockets do, and what older releases signal with an IOException) and a write fails.
 */
internal class ThreadedByteStream(
    private val io: BlockingIo,
    name: String,
    /** Builds the error for a failed read or write (LinkFailed for RFCOMM, NetworkFailed for TCP). */
    private val failure: (String) -> CarPlayError,
) : ByteStream {
    private val closed = AtomicBoolean(false)
    private val reader: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("$name-read"))
    private val writer: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("$name-write"))

    /** Whether [close] ran (for tests and logs). */
    val isClosed: Boolean get() = closed.get()

    override suspend fun read(buffer: ByteArray): Int = onThread(reader) { io.read(buffer) } ?: END_OF_STREAM

    override suspend fun write(bytes: ByteArray) {
        onThread(writer) { io.write(bytes) } ?: throw CarPlayException(failure("write on a closed stream"))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            io.close()
        } catch (_: IOException) {
            // Closing is best effort; the stream counts as closed either way.
        }
        reader.shutdown()
        writer.shutdown()
    }

    /** Runs [block] on [executor]; null when the stream is (or gets) closed. */
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
                            continuation.resumeWithException(CarPlayException(failure(e.toString()), e))
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                continuation.resume(null) // Closed between the check and the hand-off.
            }
        }
    }

    private companion object {
        const val END_OF_STREAM = -1
    }
}

/** Named daemon threads, so a stuck socket thread never keeps the process alive. */
internal fun daemonThreads(name: String): ThreadFactory =
    ThreadFactory { runnable ->
        Thread(runnable, name).apply {
            isDaemon =
                true
        }
    }

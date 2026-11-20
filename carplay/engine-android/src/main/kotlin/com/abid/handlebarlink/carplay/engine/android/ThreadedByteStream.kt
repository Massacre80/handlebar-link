package com.abid.handlebarlink.carplay.engine.android

import android.os.Process
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
 * dispatcher). Cancelling a read closes the socket, the only way to abort a blocked socket call (Android "Connect
 * Bluetooth devices": `close()` from another thread); a stream has one reader, its owner. Cancelling a write only stops
 * waiting for it: the bytes already handed to the writer thread are still written whole and in order, so a cancelled
 * writer (one of several sharing an event channel) never cuts a frame in half, skips an encrypted frame's counter or
 * closes the stream for the others. A stuck write ends with the socket (TCP_USER_TIMEOUT, or the owner's [close]).
 * After [close], a read returns -1 (what Android 17 RFCOMM sockets do, and what older releases signal with an
 * IOException) and a write fails. Any other exception from the socket is the port's error too, never an uncaught one
 * on a bare thread.
 */
internal class ThreadedByteStream(
    private val io: BlockingIo,
    name: String,
    /** The reader thread's `Process` priority (the screen stream's, DiPlay ScreenStream.kt:75-76); null keeps it. */
    readPriority: Int? = null,
    /** Builds the error for a failed read or write (LinkFailed for RFCOMM, NetworkFailed for TCP). */
    private val failure: (String) -> CarPlayError,
) : ByteStream {
    private val closed = AtomicBoolean(false)
    private val reader: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("$name-read", readPriority))
    private val writer: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("$name-write"))

    /** Whether [close] ran (for tests and logs). */
    val isClosed: Boolean get() = closed.get()

    override suspend fun read(buffer: ByteArray): Int =
        onThread(reader, closeOnCancel = true) { io.read(buffer) } ?: END_OF_STREAM

    override suspend fun write(bytes: ByteArray) {
        onThread(writer, closeOnCancel = false) { io.write(bytes) }
            ?: throw CarPlayException(failure("write on a closed stream"))
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

    /**
     * Runs [block] on [executor]; null when the stream is (or gets) closed. [closeOnCancel]: a cancelled wait closes
     * the stream (a blocked read); otherwise the block still runs to its end and only its result is dropped.
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
                            continuation.resumeWithException(CarPlayException(failure(e.toString()), e))
                        }
                    } catch (e: RuntimeException) {
                        continuation.resumeWithException(CarPlayException(failure(e.toString()), e))
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

/**
 * Named daemon threads, so a stuck socket thread never keeps the process alive; with a [priority], each thread first
 * sets that `Process` priority for itself (a refusal keeps the default).
 */
internal fun daemonThreads(
    name: String,
    priority: Int? = null,
): ThreadFactory =
    ThreadFactory { runnable ->
        val body =
            if (priority == null) {
                runnable
            } else {
                Runnable {
                    threadPriority(priority)
                    runnable.run()
                }
            }
        Thread(body, name).apply { isDaemon = true }
    }

/** `Process.setThreadPriority` for the calling thread; a refusal keeps the default priority. */
internal fun threadPriority(priority: Int) {
    try {
        Process.setThreadPriority(priority)
    } catch (_: SecurityException) {
        // Keeps the default priority.
    } catch (_: IllegalArgumentException) {
        // Keeps the default priority.
    }
}

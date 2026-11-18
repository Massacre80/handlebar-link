package com.abid.handlebarlink.carplay.engine.android

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs a blocking [connect] of [socket] on a thread of its own and suspends until it ends. Cancelling closes the
 * socket from the caller's side, which is what aborts a blocked connect (Android "Connect Bluetooth devices":
 * "`close()` … will immediately abort ongoing operations"), so no socket stays open after a cancelled dial
 * (E-connection.md E8). A failed connect closes the socket too; [failure] turns the exception into the port's error.
 */
internal suspend fun <S : Any> connectOnOwnThread(
    threadName: String,
    socket: S,
    connect: (S) -> Unit,
    close: (S) -> Unit,
    failure: (Exception) -> CarPlayException,
): S =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { closeQuietly(socket, close) }
        val thread =
            Thread({
                try {
                    connect(socket)
                    continuation.resume(socket)
                } catch (e: IOException) {
                    closeQuietly(socket, close)
                    continuation.resumeWithException(failure(e))
                } catch (e: SecurityException) {
                    closeQuietly(socket, close)
                    continuation.resumeWithException(failure(e))
                }
            }, threadName)
        thread.isDaemon = true
        thread.start()
    }

private fun <S> closeQuietly(
    socket: S,
    close: (S) -> Unit,
) {
    try {
        close(socket)
    } catch (_: IOException) {
        // Already closed or never opened.
    }
}

/** Throws [error] as the CarPlayException every port failure must be. */
internal fun fail(
    error: CarPlayError,
    cause: Throwable? = null,
): Nothing = throw CarPlayException(error, cause)

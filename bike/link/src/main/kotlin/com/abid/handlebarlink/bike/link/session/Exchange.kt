package com.abid.handlebarlink.bike.link.session

import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.gatt.GattLink
import com.abid.handlebarlink.bike.link.machine.Timeouts
import com.abid.handlebarlink.bike.protocol.ReplyMatch
import com.abid.handlebarlink.bike.protocol.SabFrame
import com.abid.handlebarlink.bike.protocol.SabSafety
import com.abid.handlebarlink.core.log.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Request/reply over a control point: write a frame, wait for the matching notification. */
internal class Exchange(
    private val link: GattLink,
    private val log: Logger,
) {
    /**
     * Writes [frame] and waits [Timeouts.REPLY_MS] for the reply [match] accepts; null on timeout.
     * The reply is subscribed to *before* the write, so a fast answer is never missed.
     */
    suspend fun <T> request(
        service: UUID,
        characteristic: UUID,
        frame: SabFrame,
        match: (ByteArray) -> ReplyMatch<T>,
    ): T? =
        coroutineScope {
            val reply =
                async(start = CoroutineStart.UNDISPATCHED) {
                    link.notifications
                        .filter { it.characteristic == characteristic }
                        .mapNotNull { accept(match(it.value), frame) }
                        .first()
                }
            write(service, characteristic, frame.toByteArray())
            val result = withTimeoutOrNull(Timeouts.REPLY_MS) { reply.await() }
            reply.cancel()
            result
        }

    /** Runs [attempt] up to [tries] times until it returns a value; null if it never does. */
    suspend fun <T> retrying(
        tries: Int,
        what: Pair<String, Any?>,
        attempt: suspend () -> T?,
    ): T? {
        repeat(tries) { index ->
            attempt()?.let { return it }
            log.warn("link.setup.no_reply", what, "try" to index + 1, "of" to tries)
        }
        return null
    }

    /** Writes [value] after checking it with [SabSafety] (never the DFU service, never command 0x0B). */
    suspend fun write(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    ) {
        check(SabSafety.isWriteAllowed(service, characteristic, value)) { "writing to $characteristic is forbidden" }
        timed(GattOp.WRITE, Timeouts.OPERATION_MS) { link.write(service, characteristic, value) }
    }

    private fun <T> accept(
        match: ReplyMatch<T>,
        frame: SabFrame,
    ): T? =
        when (match) {
            is ReplyMatch.Matched -> {
                match.reply
            }

            is ReplyMatch.Malformed -> {
                log.warn("link.setup.reply.malformed", "request" to frame, "reason" to match.reason)
                null
            }

            // RoadSync's "Corruption" and "Unsync": ignored (SAB §5.2).
            ReplyMatch.Echo, ReplyMatch.OtherRequest -> {
                null
            }
        }
}

package com.abid.handlebarlink.carplay.protocol.iap2

/**
 * Cuts the RFCOMM byte stream into iAP2 frames. Resynchronises one byte at a time after a bad start or
 * header checksum (DiPlay skips the whole 9 bytes and can lose a real frame start, spec A §8.5); a
 * frame with a bad payload checksum is dropped whole, as in DiPlay (Iap2LinkEngine.kt:281-295).
 */
internal class FrameReader {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0

    /** Frames dropped for a bad checksum or start, for diagnostics. */
    var discardedBytes: Long = 0
        private set

    val buffered: Int get() = end - start

    /** @throws Iap2LinkException when more is buffered than any frame can need. */
    fun append(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length == 0) return
        if (buffered + length > MAX_BUFFERED) throw Iap2LinkException("iAP2 receive buffer overflow")
        if (end + length > buffer.size) {
            val needed = buffered + length
            val target = if (needed <= buffer.size) buffer else ByteArray(maxOf(needed, buffer.size * 2))
            buffer.copyInto(target, 0, start, end)
            buffer = target
            end -= start
            start = 0
        }
        bytes.copyInto(buffer, end, offset, offset + length)
        end += length
    }

    /**
     * Looks for the iAP2 marker; drops anything before it. True once it is found and consumed. Unlike DiPlay,
     * which fails unless the marker is the first thing read (Iap2LinkEngine.kt:272-274, spec A §8.4), stray
     * bytes before it are skipped.
     */
    fun takeMarker(): Boolean {
        val marker = Iap2Frame.MARKER
        while (buffered >= marker.size) {
            if ((marker.indices).all { buffer[start + it] == marker[it] }) {
                start += marker.size
                return true
            }
            start++
            discardedBytes++
        }
        return false
    }

    /** The next complete, valid frame, or null when more bytes are needed. */
    fun nextFrame(): Iap2Frame? {
        var frame: Iap2Frame? = null
        var waiting = false
        while (frame == null && !waiting && buffered >= Iap2Frame.HEADER_BYTES) {
            val length = (u8(2) shl 8) or u8(3)
            when {
                !headerValid(length) -> {
                    start++
                    discardedBytes++
                }

                buffered < length -> {
                    waiting = true
                }

                else -> {
                    frame = decode(length)
                    start += length
                    if (frame == null) discardedBytes += length
                }
            }
        }
        return frame
    }

    private fun headerValid(length: Int): Boolean {
        val startsFrame = buffer[start] == Iap2Frame.START_HIGH && buffer[start + 1] == Iap2Frame.START_LOW
        return startsFrame && length >= Iap2Frame.HEADER_BYTES &&
            Iap2Frame.checksum(buffer, start, Iap2Frame.HEADER_BYTES) == 0
    }

    private fun decode(length: Int): Iap2Frame? {
        val payload =
            if (length == Iap2Frame.HEADER_BYTES) {
                null
            } else {
                val from = start + Iap2Frame.HEADER_BYTES
                val to = start + length
                if (Iap2Frame.checksum(buffer, from, length - Iap2Frame.HEADER_BYTES) != 0) return null
                buffer.copyOfRange(from, to - Iap2Frame.CHECKSUM_BYTES)
            }
        return Iap2Frame(u8(4), u8(5), u8(6), u8(7), payload)
    }

    private fun u8(at: Int): Int = buffer[start + at].toInt() and 0xFF

    private companion object {
        const val INITIAL_CAPACITY = 4096

        /** One maximum frame plus a full read chunk. */
        const val MAX_BUFFERED = Iap2Frame.MAX_FRAME_BYTES * 2
    }
}

/** A link-layer failure; the link is dead after it. */
public class Iap2LinkException(
    message: String,
) : Exception(message)

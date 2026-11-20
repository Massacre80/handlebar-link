package com.abid.handlebarlink.carplay.protocol.stream

import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.pairing.counterNonce
import java.security.GeneralSecurityException

/**
 * Reads one CarPlay screen stream (type 110 or 111, TCP) from the bytes the socket delivers, as DiPlay's
 * ScreenStream.kt:74-147 and ScreenCodec (ScreenStream.kt:153-247; spec C §8 "Framing per stream").
 *
 * Each message is a 128-byte header and a body ([HEADER_BYTES]):
 * - header bytes 0..3: body size, u32 little-endian (ScreenStream.kt:84, 243-247);
 * - header byte 4: opcode, 0 = picture, 1 = codec configuration (ScreenStream.kt:103, 143-145);
 * - header bytes 8..15: the iPhone's frame time, NTP 32.32 little-endian (ScreenStream.kt:156-167);
 * - the rest of the header is not interpreted, but authenticated.
 *
 * A picture body is ChaCha20-Poly1305 ciphertext and tag under the stream's
 * [StreamKeys.output] key, nonce = 4 zero bytes + the frame counter as LE64, and the whole 128-byte
 * header as associated data (ScreenStream.kt:107, 169-170). The counter starts at 0 and counts only the
 * pictures that were decrypted (ScreenStream.kt:104-109). A configuration body is clear: an avcC or hvcC
 * record (ScreenStream.kt:119-124, 205-214).
 *
 * As DiPlay, a picture shorter than the tag and a message with any other opcode are skipped without
 * touching the counter (ScreenStream.kt:105, 103-125); a body over [MAX_BODY_BYTES] or above 2^31 − 1
 * (DiPlay reads it as a negative Int) and a picture that does not authenticate end the stream
 * (ScreenStream.kt:85, 128-129, 92-98). Those throw [MediaStreamException]; the reader is then closed for
 * good, as the iPhone's counter can no longer be followed.
 *
 * Bytes may arrive split or joined at any point. Not thread-safe: one reader per stream, fed by one thread.
 *
 * Each picture is decrypted into an array from [pictureBuffers] (asked for at least the body's size), so the screen
 * can hand its buffers back for reuse (docs/PLAN.md performance rules: decrypt into reused buffers); the picture's
 * bytes are the first [ScreenFrame.length] of it.
 */
public class ScreenStreamReader(
    key: ByteArray,
    cipherName: String,
    private val pictureBuffers: (Int) -> ByteArray = ::ByteArray,
) {
    private val cipher = ChaCha20Poly1305(key, cipherName)
    private val header = ByteArray(HEADER_BYTES)
    private var headerFilled = 0
    private var body = ByteArray(0)
    private var bodySize = -1
    private var bodyFilled = 0
    private var frameCounter = 0L
    private var failed = false

    private val readBuffer = ByteArray(READ_BUFFER_BYTES)
    private val pending = ArrayDeque<ScreenFrame>()

    /** Pictures decrypted so far; the next picture's nonce counter. */
    public val decryptedPictures: Long get() = frameCounter

    /**
     * Adds [length] bytes of [bytes] from [offset]; returns the frames they completed, in order.
     *
     * @throws MediaStreamException when the stream must end (see the class description). Every later call
     *   throws [IllegalStateException].
     */
    public fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ): List<ScreenFrame> {
        check(!failed) { "The screen stream already failed" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Slice out of range" }
        val frames = ArrayList<ScreenFrame>(1)
        var at = offset
        val end = offset + length
        while (at < end) {
            if (bodySize < 0) {
                val n = minOf(HEADER_BYTES - headerFilled, end - at)
                bytes.copyInto(header, headerFilled, at, at + n)
                headerFilled += n
                at += n
                if (headerFilled == HEADER_BYTES) startBody()
            } else {
                val n = minOf(bodySize - bodyFilled, end - at)
                bytes.copyInto(body, bodyFilled, at, at + n)
                bodyFilled += n
                at += n
            }
            if (bodySize >= 0 && bodyFilled == bodySize) {
                message()?.let(frames::add)
                headerFilled = 0
                bodySize = -1
                bodyFilled = 0
            }
        }
        return frames
    }

    /**
     * The next frame, reading from the connection with [read] as needed; null at the end of the stream.
     * [read] fills the start of its buffer and returns the count, or -1 at the end (a socket's
     * `read(buffer)`). As DiPlay, an end in the middle of a message is a normal end (ScreenStream.kt:83-86,
     * 128-137).
     *
     * @throws MediaStreamException as [feed].
     */
    public suspend fun next(read: suspend (ByteArray) -> Int): ScreenFrame? {
        while (pending.isEmpty()) {
            val n = read(readBuffer)
            if (n < 0) return null
            pending.addAll(feed(readBuffer, 0, n))
        }
        return pending.removeFirst()
    }

    private fun startBody() {
        val size =
            (header[0].toInt() and 0xFF) or
                ((header[1].toInt() and 0xFF) shl 8) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 24)
        if (size < 0 || size > MAX_BODY_BYTES) {
            failed = true
            throw MediaStreamException("Screen frame body of ${size.toUInt()} bytes is over $MAX_BODY_BYTES")
        }
        if (body.size < size) body = ByteArray(size)
        bodySize = size
    }

    private fun message(): ScreenFrame? =
        when (header[OPCODE_OFFSET].toInt() and 0xFF) {
            OPCODE_PICTURE -> picture()
            OPCODE_CONFIG -> config(body.copyOf(bodySize), senderNanos(header))
            else -> null
        }

    private fun picture(): ScreenFrame.Picture? {
        if (bodySize < ChaCha20Poly1305.TAG_BYTES) return null
        val payload = pictureBuffers(bodySize)
        val length =
            try {
                cipher.openInto(counterNonce(frameCounter), header, body, bodySize, payload)
            } catch (e: GeneralSecurityException) {
                failed = true
                throw MediaStreamException("Screen frame $frameCounter did not authenticate", e)
            }
        frameCounter++
        return ScreenFrame.Picture(senderNanos(header), lengthPrefixedToAnnexB(payload, length), length)
    }

    /** Sizes and opcodes of the screen stream (ScreenStream.kt:140-147). */
    public companion object {
        /** How much [next] asks [next]'s read function for at a time. */
        public const val READ_BUFFER_BYTES: Int = 64 * 1024

        /** Header length (ScreenStream.kt:142). */
        public const val HEADER_BYTES: Int = 128

        /** Largest body accepted (ScreenStream.kt:146). */
        public const val MAX_BODY_BYTES: Int = 8 * 1024 * 1024

        private const val OPCODE_OFFSET = 4
        private const val OPCODE_PICTURE = 0
        private const val OPCODE_CONFIG = 1
        private const val SENDER_TIME_OFFSET = 8
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /**
         * The frame time of a screen header in nanoseconds: bytes 8..15 as a little-endian NTP 32.32
         * value; 0 when the header is shorter (ScreenStream.kt:155-165).
         */
        public fun senderNanos(header: ByteArray): Long {
            if (header.size < SENDER_TIME_OFFSET + 8) return 0L
            var raw = 0L
            for (index in 7 downTo 0) raw = (raw shl 8) or (header[SENDER_TIME_OFFSET + index].toLong() and 0xFF)
            return (raw ushr 32) * NANOS_PER_SECOND + ((raw and 0xFFFF_FFFFL) * NANOS_PER_SECOND ushr 32)
        }

        /**
         * Replaces each 4-byte big-endian NAL length in the first [length] bytes of [payload] with an Annex B start
         * code, in place, and returns [payload]. A payload that already starts with a start code, or whose lengths do
         * not tile those bytes exactly with NALs of at least one byte, is returned untouched (ScreenStream.kt:172-203).
         */
        public fun lengthPrefixedToAnnexB(
            payload: ByteArray,
            length: Int = payload.size,
        ): ByteArray {
            if (length < 4 || payload.startsWithStartCode() || !isLengthPrefixed(payload, length)) return payload
            var offset = 0
            while (offset <= length - 4) {
                val length = readU32Be(payload, offset)
                payload[offset] = 0
                payload[offset + 1] = 0
                payload[offset + 2] = 0
                payload[offset + 3] = 1
                offset += 4 + length
            }
            return payload
        }

        /** Whether 4-byte lengths, each followed by that many (at least one) bytes, tile [size] bytes exactly. */
        private fun isLengthPrefixed(
            payload: ByteArray,
            size: Int,
        ): Boolean {
            var offset = 0
            var valid = true
            while (valid && offset <= size - 4) {
                val length = readU32Be(payload, offset)
                offset += 4
                // Subtraction, not `offset + length`, so that a length near 2^31 cannot wrap.
                valid = length > 0 && length <= size - offset
                if (valid) offset += length
            }
            return valid && offset == size
        }

        /**
         * The codec and decoder record of a configuration body (ScreenStream.kt:205-222): the bytes after an
         * `hvcC` or `avcC` fourcc searched from offset 4, else the whole body, H.264 when it looks like an
         * avcC record (at least one SPS whose first NAL is type 7), otherwise H.265.
         */
        public fun config(
            payload: ByteArray,
            senderNanos: Long = 0L,
        ): ScreenFrame.Config {
            for (index in 4..payload.size - 4) {
                val codec =
                    when (String(payload, index, 4, Charsets.US_ASCII)) {
                        "hvcC" -> VideoCodec.H265
                        "avcC" -> VideoCodec.H264
                        else -> continue
                    }
                return ScreenFrame.Config(codec, payload.copyOfRange(index + 4, payload.size), senderNanos)
            }
            val codec = if (looksLikeAvcC(payload)) VideoCodec.H264 else VideoCodec.H265
            return ScreenFrame.Config(codec, payload, senderNanos)
        }

        private fun looksLikeAvcC(payload: ByteArray): Boolean =
            payload.size >= 9 &&
                (payload[5].toInt() and 0x1F) >= 1 &&
                8 + readU16Be(payload, 6) <= payload.size &&
                (payload[8].toInt() and 0x1F) == 7

        private fun ByteArray.startsWithStartCode(): Boolean =
            size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() &&
                this[3] == 1.toByte()
    }
}

/**
 * One message of a screen stream: a codec configuration ([isConfig], [payload] = the decoder record) or a
 * picture ([payload] = the Annex B access unit). The stream type (110 or 111) is the caller's.
 */
public sealed interface ScreenFrame {
    /** True for a codec configuration, false for a picture. */
    public val isConfig: Boolean

    /** The iPhone's time stamp from the header (bytes 8..15, NTP 32.32 LE) in nanoseconds. */
    public val senderNanos: Long

    /** The decoder record (configuration) or the Annex B access unit (picture), in its first [length] bytes. */
    public val payload: ByteArray

    /** How many bytes of [payload] are the message (a picture's buffer may be longer). */
    public val length: Int

    /**
     * A codec configuration (opcode 1, clear): [payload] is the avcC (H.264) or hvcC (H.265) decoder
     * configuration record that DiPlay hands its decoder (ScreenStream.kt:119-124; AndroidMediaSink.kt:507-515).
     * DiPlay does not use a configuration's time stamp.
     */
    public class Config(
        /** The codec the record is for. */
        public val codec: VideoCodec,
        override val payload: ByteArray,
        override val senderNanos: Long = 0L,
    ) : ScreenFrame {
        override val isConfig: Boolean get() = true

        override val length: Int get() = payload.size

        /**
         * The first SPS and first PPS of an avcC [payload], without start codes; each is empty when the record
         * does not hold one (DiPlay MediaCodecSupport.kt:17-26, 172-185).
         */
        public fun avcParameterSets(): AvcParameterSets {
            val data = payload
            // A record under 7 bytes has no SPS count; the cursor (6) is then already at its end.
            val sps = if (data.size < 7) emptyList() else parameterSets(data, 6, data[5].toInt() and 0x1F)
            val cursor = 6 + sps.sumOf { it.size + 2 }
            if (cursor >= data.size) return AvcParameterSets(ByteArray(0), ByteArray(0))
            val pps = parameterSets(data, cursor + 1, data[cursor].toInt() and 0xFF)
            return AvcParameterSets(sps.firstOrNull() ?: ByteArray(0), pps.firstOrNull() ?: ByteArray(0))
        }

        private fun parameterSets(
            source: ByteArray,
            offset: Int,
            count: Int,
        ): List<ByteArray> {
            val sets = ArrayList<ByteArray>(count)
            var cursor = offset
            var index = 0
            while (index < count && cursor + 2 <= source.size) {
                index++
                val length = readU16Be(source, cursor)
                cursor += 2
                if (cursor + length > source.size) break
                sets.add(source.copyOfRange(cursor, cursor + length))
                cursor += length
            }
            return sets
        }
    }

    /**
     * A decrypted picture (opcode 0): the first [length] bytes of [payload] are the access unit with start codes in
     * place of the NAL lengths (or the decrypted bytes as they are, when the lengths are malformed), [senderNanos] the
     * iPhone's frame time (ScreenStream.kt:117).
     */
    public class Picture(
        override val senderNanos: Long,
        override val payload: ByteArray,
        override val length: Int = payload.size,
    ) : ScreenFrame {
        override val isConfig: Boolean get() = false
    }
}

/** The parameter sets of an avcC record, without start codes; empty when absent. */
public class AvcParameterSets(
    /** The first sequence parameter set. */
    public val sps: ByteArray,
    /** The first picture parameter set. */
    public val pps: ByteArray,
)

/** The screen's codec (ScreenStream.kt:13). */
public enum class VideoCodec {
    /** H.264 / AVC: the one we advertise (no `hevcInfo` in /info, spec D §4). */
    H264,

    /** H.265 / HEVC. */
    H265,
}

/** A media stream that cannot go on: an oversized frame, or one that does not authenticate. */
public class MediaStreamException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun readU16Be(
    source: ByteArray,
    offset: Int,
): Int = ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

private fun readU32Be(
    source: ByteArray,
    offset: Int,
): Int =
    ((source[offset].toInt() and 0xFF) shl 24) or
        ((source[offset + 1].toInt() and 0xFF) shl 16) or
        ((source[offset + 2].toInt() and 0xFF) shl 8) or
        (source[offset + 3].toInt() and 0xFF)

package com.abid.handlebarlink.carplay.engine.android.video

import java.nio.ByteBuffer

/**
 * The decoder configuration of an H.264 stream: its SPS and PPS as MediaCodec's `csd-0` and `csd-1` (each NAL unit
 * behind a 4-byte start code, as DiPlay builds them: `AndroidMediaSink.kt` configureDecoder, `START_CODE + sps`), and
 * how the stream's pictures are framed.
 *
 * The iPhone sends an `avcC` record (ISO/IEC 14496-15 §5.3.3.1; DiPlay `ScreenStream.kt:120`,
 * `ScreenCodec.detectConfig`) and then length-prefixed pictures (DiPlay `MediaCodecSupport.toAnnexB`). An Annex B
 * configuration (start codes, as ffmpeg writes a raw `.h264`) is accepted too; its pictures are then Annex B.
 */
internal class AvcConfig(
    /** `csd-0`: every SPS, each behind a start code. */
    val csd0: ByteArray,
    /** `csd-1`: every PPS, each behind a start code. */
    val csd1: ByteArray,
    /** Bytes in each picture NAL unit's length prefix (1, 2 or 4); [ANNEX_B] when pictures carry start codes. */
    val nalLengthSize: Int,
) {
    /** Whether [other] configures the decoder the same way (an unchanged config keeps the running decoder). */
    fun sameAs(other: AvcConfig): Boolean =
        nalLengthSize == other.nalLengthSize && csd0.contentEquals(other.csd0) && csd1.contentEquals(other.csd1)

    companion object {
        /** [nalLengthSize] of a stream framed with start codes. */
        const val ANNEX_B = 0
    }
}

/** H.264 pictures of the screen stream: framing and keyframe detection. */
internal object Avc {
    /** [writeAnnexB]: the picture's NAL lengths do not add up. */
    const val MALFORMED = -1

    /** [writeAnnexB]: the picture does not fit the decoder's input buffer. */
    const val TOO_LARGE = -2

    const val NAL_TYPE_MASK = 0x1f
    private const val NAL_SLICE = 1
    private const val NAL_IDR = 5
    private const val BYTE_MASK = 0xff
    private const val BITS_PER_BYTE = 8
    private const val START_CODE_SIZE = 4
    private const val SHORT_START_CODE_SIZE = 3

    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * Whether the picture starts an independent reference chain (its first slice is IDR, nal_unit_type 5; DiPlay
     * `MediaCodecSupport.isRandomAccess`). False for anything malformed.
     */
    fun isIdr(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        nalLengthSize: Int,
    ): Boolean {
        var result = false
        forEachNal(bytes, offset, length, nalLengthSize) { start, _ ->
            val type = bytes[start].toInt() and NAL_TYPE_MASK
            result = type == NAL_IDR
            type !in NAL_SLICE..NAL_IDR // keep looking until the first slice
        }
        return result
    }

    /**
     * Writes the picture into [out] as Annex B, the framing Android's decoders take, converting length prefixes to
     * start codes while copying (the only copy between the decrypt and the decoder). Returns the bytes written,
     * [MALFORMED] or [TOO_LARGE]; nothing is written unless the whole picture fits. A picture that already starts
     * with a start code is copied as it is (DiPlay `toAnnexB` does the same).
     */
    fun writeAnnexB(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        nalLengthSize: Int,
        out: ByteBuffer,
    ): Int {
        val prefixed = nalLengthSize != AvcConfig.ANNEX_B && lengthsAddUp(bytes, offset, length, nalLengthSize)
        val size =
            when {
                prefixed -> annexBSize(bytes, offset, length, nalLengthSize)
                inBounds(bytes, offset, length) && startCodeAt(bytes, offset, offset + length) > 0 -> length
                else -> MALFORMED
            }
        return when {
            size < 0 -> MALFORMED
            size > out.remaining() -> TOO_LARGE
            prefixed -> size.also { copyAsAnnexB(bytes, offset, length, nalLengthSize, out) }
            else -> size.also { out.put(bytes, offset, length) }
        }
    }

    /**
     * Calls [visit] with each NAL unit's start and size until it returns false. Length-prefixed input that does not
     * add up is read as Annex B; Annex B input without a start code visits nothing.
     */
    inline fun forEachNal(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        nalLengthSize: Int,
        visit: (start: Int, size: Int) -> Boolean,
    ) {
        if (nalLengthSize != AvcConfig.ANNEX_B && lengthsAddUp(bytes, offset, length, nalLengthSize)) {
            val end = offset + length
            var at = offset
            var going = true
            while (going && at < end) {
                val size = readLength(bytes, at, nalLengthSize)
                at += nalLengthSize
                going = visit(at, size)
                at += size
            }
        } else {
            forEachAnnexBNal(bytes, offset, length, visit)
        }
    }

    inline fun forEachAnnexBNal(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        visit: (start: Int, size: Int) -> Boolean,
    ) {
        val end = offset + length
        var codeSize = if (inBounds(bytes, offset, length)) startCodeAt(bytes, offset, end) else 0
        var nalStart = offset + codeSize
        var going = codeSize > 0
        while (going && nalStart < end) {
            var next = nalStart
            codeSize = 0
            while (next < end && codeSize == 0) {
                codeSize = startCodeAt(bytes, next, end)
                if (codeSize == 0) next++
            }
            // A 4-byte start code's leading zero is not part of the NAL unit before it.
            if (next > nalStart) going = visit(nalStart, next - nalStart)
            nalStart = next + codeSize
            if (codeSize == 0) going = false
        }
    }

    fun lengthsAddUp(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        nalLengthSize: Int,
    ): Boolean {
        val end = offset + length
        var at = offset
        var ok = length > 0 && inBounds(bytes, offset, length)
        while (ok && at < end) {
            ok = at + nalLengthSize <= end
            if (ok) {
                val size = readLength(bytes, at, nalLengthSize)
                at += nalLengthSize
                ok = size > 0 && size <= end - at
                at += size
            }
        }
        return ok
    }

    /** The size of the start code at [at] (3 or 4), or 0. */
    fun startCodeAt(
        bytes: ByteArray,
        at: Int,
        end: Int,
    ): Int =
        when {
            at + SHORT_START_CODE_SIZE > end || bytes[at].toInt() != 0 || bytes[at + 1].toInt() != 0 -> 0
            bytes[at + 2].toInt() == 1 -> SHORT_START_CODE_SIZE
            at + START_CODE_SIZE > end -> 0
            bytes[at + 2].toInt() == 0 && bytes[at + SHORT_START_CODE_SIZE].toInt() == 1 -> START_CODE_SIZE
            else -> 0
        }

    fun readLength(
        bytes: ByteArray,
        at: Int,
        size: Int,
    ): Int {
        var value = 0
        for (i in 0 until size) value = (value shl BITS_PER_BYTE) or (bytes[at + i].toInt() and BYTE_MASK)
        return value
    }

    fun inBounds(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean = offset >= 0 && length >= 0 && offset + length <= bytes.size

    private fun annexBSize(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        nalLengthSize: Int,
    ): Int {
        var total = 0
        forEachNal(bytes, offset, length, nalLengthSize) { _, size ->
            total += START_CODE_SIZE + size
            true
        }
        return total
    }

    private fun copyAsAnnexB(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        nalLengthSize: Int,
        out: ByteBuffer,
    ) {
        forEachNal(bytes, offset, length, nalLengthSize) { start, size ->
            out.put(START_CODE)
            out.put(bytes, start, size)
            true
        }
    }
}

/** Reads the screen stream's codec configuration: an `avcC` record or Annex B SPS/PPS. */
internal object AvcConfigParser {
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8

    // avcC (ISO/IEC 14496-15 §5.3.3.1): version, profile, compatibility, level, lengthSizeMinusOne, numOfSPS.
    private const val AVCC_VERSION = 1
    private const val AVCC_HEADER_SIZE = 6
    private const val AVCC_LENGTH_SIZE_OFFSET = 4
    private const val AVCC_LENGTH_SIZE_MASK = 0x03
    private const val AVCC_SPS_COUNT_OFFSET = 5
    private const val AVCC_SPS_COUNT_MASK = 0x1f
    private const val UNUSED_LENGTH_SIZE = 3 // lengthSizeMinusOne 2 is not allowed

    /** The configuration in [bytes] (`avcC` or Annex B), or null when it holds no SPS and PPS. */
    fun parse(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): AvcConfig? =
        when {
            length <= 0 || !Avc.inBounds(bytes, offset, length) -> {
                null
            }

            Avc.startCodeAt(bytes, offset, offset + length) > 0 -> {
                parseAnnexB(bytes, offset, length)
            }

            bytes[offset].toInt() == AVCC_VERSION && length >= AVCC_HEADER_SIZE -> {
                parseAvcc(
                    bytes,
                    offset,
                    offset + length,
                )
            }

            else -> {
                null
            }
        }

    private fun parseAvcc(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): AvcConfig? {
        val lengthSize = (bytes[start + AVCC_LENGTH_SIZE_OFFSET].toInt() and AVCC_LENGTH_SIZE_MASK) + 1
        val reader = SetReader(bytes, start + AVCC_HEADER_SIZE, end)
        val sps = reader.sets(bytes[start + AVCC_SPS_COUNT_OFFSET].toInt() and AVCC_SPS_COUNT_MASK)
        val pps = sps?.let { reader.byte() }?.let { reader.sets(it) }
        return if (lengthSize == UNUSED_LENGTH_SIZE || sps == null ||
            pps == null
        ) {
            null
        } else {
            configOf(sps, pps, lengthSize)
        }
    }

    private fun parseAnnexB(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): AvcConfig? {
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        Avc.forEachAnnexBNal(bytes, offset, length) { at, size ->
            when (bytes[at].toInt() and Avc.NAL_TYPE_MASK) {
                NAL_SPS -> {
                    sps += bytes.copyOfRange(at, at + size)
                }

                NAL_PPS -> {
                    pps += bytes.copyOfRange(at, at + size)
                }

                else -> {}
            }
            true
        }
        return configOf(sps, pps, AvcConfig.ANNEX_B)
    }

    private fun configOf(
        sps: List<ByteArray>,
        pps: List<ByteArray>,
        lengthSize: Int,
    ): AvcConfig? {
        val valid =
            sps.isNotEmpty() &&
                pps.isNotEmpty() &&
                sps.all { (it[0].toInt() and Avc.NAL_TYPE_MASK) == NAL_SPS } &&
                pps.all { (it[0].toInt() and Avc.NAL_TYPE_MASK) == NAL_PPS }
        return if (valid) AvcConfig(withStartCodes(sps), withStartCodes(pps), lengthSize) else null
    }

    private fun withStartCodes(sets: List<ByteArray>): ByteArray {
        val out = ByteBuffer.allocate(sets.sumOf { Avc.START_CODE.size + it.size })
        sets.forEach { out.put(Avc.START_CODE).put(it) }
        return out.array()
    }

    /** Reads an `avcC` record's parameter sets: a count, then each behind a 16-bit length. */
    private class SetReader(
        private val bytes: ByteArray,
        private var at: Int,
        private val end: Int,
    ) {
        /** One byte, or null at the end. */
        fun byte(): Int? = if (at < end) (bytes[at++].toInt() and BYTE_MASK) else null

        /** [count] length-prefixed sets, or null when they are cut short or empty. */
        fun sets(count: Int): List<ByteArray>? {
            val out = ArrayList<ByteArray>(count)
            var ok = true
            repeat(count) {
                val size = if (ok && at + SET_LENGTH_SIZE <= end) Avc.readLength(bytes, at, SET_LENGTH_SIZE) else 0
                ok = size > 0 && at + SET_LENGTH_SIZE + size <= end
                if (ok) {
                    out += bytes.copyOfRange(at + SET_LENGTH_SIZE, at + SET_LENGTH_SIZE + size)
                    at += SET_LENGTH_SIZE + size
                }
            }
            return if (ok) out else null
        }

        private companion object {
            const val SET_LENGTH_SIZE = 2
            const val BYTE_MASK = 0xff
        }
    }
}

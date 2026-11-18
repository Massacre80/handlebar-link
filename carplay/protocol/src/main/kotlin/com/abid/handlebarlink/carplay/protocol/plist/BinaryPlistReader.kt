package com.abid.handlebarlink.carplay.protocol.plist

import java.math.BigInteger

/**
 * Reads Apple binary plists from the iPhone (SETUP, `/command`, `/feedback`). The input is untrusted, so
 * every reference and offset is checked, nesting is limited (which also stops cycles), the number of object
 * reads and the bytes copied out of shared data and strings are bounded (shared references cannot blow up the
 * work or the memory), containers cannot claim more entries than the input could hold, and their references are
 * read one at a time, so a container that holds itself allocates nothing per level (DiPlay's decoder limits,
 * BplistCodec.kt:36-40, :193-215, :299-311, spec D §3).
 */
public object BinaryPlistReader {
    private const val HEADER_BYTES = 8
    private const val TRAILER_BYTES = 32
    private const val MAX_DEPTH = 32
    private const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    private const val MAX_READS = 100_000

    /** DiPlay BplistCodec.kt MAX_PAYLOAD_BYTES: the largest real payload is a picture read once. */
    private const val MAX_PAYLOAD_BYTES = 4L * 1024 * 1024

    /** Payloads up to this size cost one read like any object (equal keys are stored once and read often). */
    private const val SMALL_PAYLOAD_BYTES = 64

    /** A container's list starts at most this large (DiPlay INITIAL_CAPACITY). */
    private const val INITIAL_CAPACITY = 64
    private const val SECONDS_SINCE_2001_MARKER = 0x33
    private val MAGIC = "bplist00".encodeToByteArray()

    /**
     * Decodes [bytes].
     *
     * @throws PlistException when the data is not a valid binary plist.
     */
    public fun read(bytes: ByteArray): Plist {
        if (bytes.size < HEADER_BYTES + TRAILER_BYTES || bytes.size > MAX_INPUT_BYTES) fail("bad size ${bytes.size}")
        if (!bytes.copyOf(HEADER_BYTES).contentEquals(MAGIC)) fail("not a bplist00")
        val t = bytes.size - TRAILER_BYTES
        val offsetSize = bytes[t + 6].toInt()
        val refSize = bytes[t + 7].toInt()
        val count = readLong(bytes, t + 8, 8)
        val top = readLong(bytes, t + 16, 8)
        val tableOffset = readLong(bytes, t + 24, 8)
        if (offsetSize !in 1..8 || refSize !in 1..8) fail("bad trailer sizes")
        if (count !in 1..bytes.size.toLong() || top !in 0 until count) fail("bad object count")
        if (tableOffset !in HEADER_BYTES.toLong()..t.toLong() || tableOffset + count * offsetSize > t) {
            fail("bad offset table")
        }
        val offsets =
            IntArray(count.toInt()) {
                val offset = readLong(bytes, tableOffset.toInt() + it * offsetSize, offsetSize)
                if (offset !in HEADER_BYTES.toLong() until tableOffset) fail("object offset out of range")
                offset.toInt()
            }
        val allowance = 4L * bytes.size + 1024
        val decoder =
            Decoder(
                bytes,
                offsets,
                refSize,
                tableOffset.toInt(),
                budget = minOf(allowance, MAX_READS.toLong()).toInt(),
                payloadBudget = minOf(allowance, MAX_PAYLOAD_BYTES),
            )
        return decoder.read(top.toInt(), depth = 0)
    }

    private class Decoder(
        val bytes: ByteArray,
        val offsets: IntArray,
        val refSize: Int,
        val end: Int,
        var budget: Int,
        var payloadBudget: Long,
    ) {
        fun read(
            ref: Int,
            depth: Int,
        ): Plist {
            if (depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH")
            if (--budget < 0) fail("too many object reads")
            if (ref !in offsets.indices) fail("reference $ref out of range")
            val at = offsets[ref]
            val marker = bytes[at].toInt() and 0xFF
            val low = marker and 0x0F
            return when (marker ushr 4) {
                0x0, 0x1, 0x2, 0x3 -> number(marker, at)
                0x4, 0x5, 0x6 -> withCount(at, low) { start, n -> bytesOrText(marker ushr 4, start, n) }
                0xA -> container(at, low) { start, n -> array(start, n, depth) }
                0xD -> container(at, low, pairs = true) { start, n -> dict(start, n, depth) }
                else -> fail("unsupported object type %02X".format(marker))
            }
        }

        /** Booleans, integers, reals and dates. */
        private fun number(
            marker: Int,
            at: Int,
        ): Plist =
            when {
                marker == 0x08 -> Plist.Bool(false)
                marker == 0x09 -> Plist.Bool(true)
                marker ushr 4 == 0x1 -> integer(at + 1, marker and 0x0F)
                marker ushr 4 == 0x2 -> real(at + 1, marker and 0x0F)
                marker == SECONDS_SINCE_2001_MARKER -> Plist.Date(Double.fromBits(readLong(bytes, at + 1, 8)))
                else -> fail("unsupported object %02X".format(marker))
            }

        /**
         * Data (0x4), ASCII (0x5) or UTF-16BE (0x6) of [count] units at [start]. Every copy over
         * [SMALL_PAYLOAD_BYTES] is charged to the payload budget (DiPlay BplistCodec.kt:305-311).
         */
        private fun bytesOrText(
            type: Int,
            start: Int,
            count: Int,
        ): Plist {
            val length = if (type == 0x6) 2 * count else count
            if (length > SMALL_PAYLOAD_BYTES) {
                payloadBudget -= length
                if (payloadBudget < 0) fail("shared data is read more often than its size allows")
            }
            val payload = slice(start, length)
            return when (type) {
                0x4 -> Plist.Data(payload)
                0x5 -> Plist.Text(String(payload, Charsets.US_ASCII))
                else -> Plist.Text(String(payload, Charsets.UTF_16BE))
            }
        }

        /** 1, 2 and 4 bytes are unsigned, 8 signed, 16 the unsigned range up to 2^64 (Apple's writer). */
        private fun integer(
            at: Int,
            log: Int,
        ): Plist.Integer {
            if (log > 4) fail("integer of 2^$log bytes")
            val size = 1 shl log
            val raw = slice(at, size)
            val value = if (size >= 8) BigInteger(raw) else BigInteger(1, raw)
            if (value !in Plist.Integer.MIN..Plist.Integer.MAX) fail("integer out of range")
            return Plist.Integer(value)
        }

        private fun real(
            at: Int,
            log: Int,
        ): Plist.Real =
            when (log) {
                2 -> Plist.Real(Float.fromBits(readLong(bytes, at, 4).toInt()).toDouble())
                3 -> Plist.Real(Double.fromBits(readLong(bytes, at, 8)))
                else -> fail("real of 2^$log bytes")
            }

        // References are read one at a time and lists start small: a container that holds itself would
        // otherwise allocate its full count at every level before the depth limit (DiPlay BplistCodec.kt:193-215).
        private fun array(
            start: Int,
            count: Int,
            depth: Int,
        ): Plist.Array {
            val items = ArrayList<Plist>(minOf(count, INITIAL_CAPACITY))
            for (i in 0 until count) {
                val ref = readReference(bytes, start + i * refSize, refSize)
                items += read(ref, depth + 1)
            }
            return Plist.Array(items)
        }

        private fun dict(
            start: Int,
            count: Int,
            depth: Int,
        ): Plist.Dict {
            val entries = LinkedHashMap<String, Plist>(minOf(count, INITIAL_CAPACITY))
            for (i in 0 until count) {
                val keyRef = readReference(bytes, start + i * refSize, refSize)
                val valueRef = readReference(bytes, start + (count + i) * refSize, refSize)
                val key = read(keyRef, depth + 1) as? Plist.Text ?: fail("dictionary key is not a string")
                entries[key.value] = read(valueRef, depth + 1)
            }
            return Plist.Dict(entries)
        }

        private inline fun <T> container(
            at: Int,
            low: Int,
            pairs: Boolean = false,
            build: (Int, Int) -> T,
        ): T =
            withCount(at, low) { start, n ->
                val total = if (pairs) 2L * n else n.toLong()
                if (start + total * refSize > end) fail("container overruns the object table")
                build(start, n)
            }

        /** Reads the count (inline nibble, or 0xF and an integer object) and calls [use] with the payload start. */
        private inline fun <T> withCount(
            at: Int,
            low: Int,
            use: (Int, Int) -> T,
        ): T {
            if (low != 0x0F) return use(at + 1, low)
            val marker = bytes.getOrNull(at + 1)?.toInt()?.and(0xFF) ?: fail("truncated count")
            if (marker ushr 4 != 1 || marker and 0x0F > 3) fail("bad count marker")
            val size = 1 shl (marker and 0x0F)
            val count = readLong(bytes, at + 2, size)
            if (count !in 0..end.toLong()) fail("count $count out of range")
            return use(at + 2 + size, count.toInt())
        }

        private fun slice(
            start: Int,
            length: Int,
        ): ByteArray {
            if (length < 0 || start + length > end) fail("object overruns the object table")
            return bytes.copyOfRange(start, start + length)
        }
    }

    /** A container's reference at [at]; one beyond the Int range is refused, never truncated. */
    private fun readReference(
        bytes: ByteArray,
        at: Int,
        size: Int,
    ): Int {
        val ref = readLong(bytes, at, size)
        if (ref !in 0..Int.MAX_VALUE.toLong()) fail("reference $ref out of range")
        return ref.toInt()
    }

    private fun readLong(
        bytes: ByteArray,
        at: Int,
        size: Int,
    ): Long {
        if (at < 0 || at + size > bytes.size) fail("read past the end")
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun fail(reason: String): Nothing = throw PlistException("Invalid binary plist: $reason")
}

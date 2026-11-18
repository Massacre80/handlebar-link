package com.abid.handlebarlink.carplay.protocol.iap2

// iAP2 link-layer frames as DiPlay's working receiver writes them (DiPlay Iap2LinkEngine.kt:533-554,
// :660-702; spec: docs/research/carplay-spec/A-iap2-link.md §3).
//
//   FF 5A | length u16 BE (whole frame) | control | seq | ack | session | header checksum
//   [payload … | payload checksum]        (payload part only when length > 9)
//
// Both checksums are the two's complement of the 8-bit byte sum, so a valid block sums to 0 mod 256.

/** One iAP2 link frame. [payload] is null for a header-only frame (pure ACK). */
internal class Iap2Frame(
    val control: Int,
    val sequence: Int,
    val acknowledgement: Int,
    val sessionId: Int,
    val payload: ByteArray?,
) {
    val isSynchronize: Boolean get() = control and SYN != 0
    val hasAcknowledgement: Boolean get() = control and ACK != 0
    val isExtendedAcknowledgement: Boolean get() = control and EAK != 0
    val isReset: Boolean get() = control and RST != 0

    /** Data frames carry no flag but ACK, and a payload (DiPlay Iap2LinkEngine.kt:348). */
    val isData: Boolean get() = control and ACK.inv() == 0 && payload != null

    fun encode(): ByteArray {
        val length = HEADER_BYTES + (payload?.let { it.size + CHECKSUM_BYTES } ?: 0)
        require(length <= MAX_FRAME_BYTES) { "iAP2 frame of $length bytes exceeds $MAX_FRAME_BYTES" }
        val out = ByteArray(length)
        out[0] = START_HIGH
        out[1] = START_LOW
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
        out[4] = control.toByte()
        out[5] = sequence.toByte()
        out[6] = acknowledgement.toByte()
        out[7] = sessionId.toByte()
        out[8] = checksum(out, 0, HEADER_BYTES - CHECKSUM_BYTES).toByte()
        if (payload != null) {
            payload.copyInto(out, HEADER_BYTES)
            out[length - 1] = checksum(payload, 0, payload.size).toByte()
        }
        return out
    }

    override fun toString(): String =
        "Iap2Frame(control=%02X seq=%d ack=%d session=%d payload=%d)"
            .format(control, sequence, acknowledgement, sessionId, payload?.size ?: -1)

    companion object {
        const val SYN = 0x80
        const val ACK = 0x40
        const val EAK = 0x20
        const val RST = 0x10

        const val START_HIGH: Byte = 0xFF.toByte()
        const val START_LOW: Byte = 0x5A
        const val HEADER_BYTES = 9
        const val CHECKSUM_BYTES = 1

        /** The length field is u16 and counts the whole frame. */
        const val MAX_FRAME_BYTES = 0xFFFF

        /** Sent before the link starts and echoed by the iPhone (DiPlay Iap2LinkEngine.kt:664). */
        val MARKER: ByteArray = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)

        /** Two's complement of the 8-bit sum of [count] bytes from [offset] (DiPlay Iap2LinkEngine.kt:701). */
        fun checksum(
            bytes: ByteArray,
            offset: Int,
            count: Int,
        ): Int {
            var sum = 0
            for (i in offset until offset + count) sum += bytes[i].toInt() and 0xFF
            return -sum and 0xFF
        }
    }
}

/** Sequence numbers are u8 and wrap 255 → 0 (DiPlay Iap2LinkEngine.kt:704-707). */
internal fun sequenceDistance(
    to: Int,
    from: Int,
): Int = (to - from) and 0xFF

internal fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xFF

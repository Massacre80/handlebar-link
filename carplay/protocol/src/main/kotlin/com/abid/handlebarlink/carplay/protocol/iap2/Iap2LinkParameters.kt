package com.abid.handlebarlink.carplay.protocol.iap2

/** A session the link carries: [id] in every frame header, [type] 0 control, 1 file transfer, 2 external accessory. */
public data class Iap2SessionDescriptor(
    /** Session id written in frame headers. */
    val id: Int,
    /** Session type. */
    val type: Int,
    /** Session version. */
    val version: Int,
)

/**
 * The link synchronization (SYN) payload: what each side may send (DiPlay Iap2LinkEngine.kt:67-104).
 * Once the iPhone answers, its values govern both directions, as in DiPlay (spec A §5).
 */
public data class Iap2LinkParameters(
    /** Packets that may be outstanding without an acknowledgement. */
    val maxOutgoing: Int,
    /** Largest whole frame, header included. */
    val maxFrameBytes: Int,
    /** Retransmit an unacknowledged packet after this long. */
    val retransmitMillis: Int,
    /** Acknowledge received data at the latest after this long. */
    val acknowledgeMillis: Int,
    /** Retransmissions of one packet before the link fails. */
    val maxRetransmissions: Int,
    /** Received packets that may wait for one cumulative acknowledgement. */
    val maxCumulativeAcknowledgements: Int,
    /** Sessions the link carries. */
    val sessions: List<Iap2SessionDescriptor>,
) {
    /** Largest payload of one frame (the frame minus header and payload checksum). */
    val maxPayloadBytes: Int get() = maxFrameBytes - Iap2Frame.HEADER_BYTES - Iap2Frame.CHECKSUM_BYTES

    /** The SYN payload bytes; u16 fields big-endian. */
    public fun encode(): ByteArray {
        val out = ByteArray(FIXED_BYTES + sessions.size * SESSION_BYTES)
        out[0] = VERSION.toByte()
        out[1] = maxOutgoing.toByte()
        out.putU16(2, maxFrameBytes)
        out.putU16(4, retransmitMillis)
        out.putU16(6, acknowledgeMillis)
        out[8] = maxRetransmissions.toByte()
        out[9] = maxCumulativeAcknowledgements.toByte()
        sessions.forEachIndexed { i, s ->
            val at = FIXED_BYTES + i * SESSION_BYTES
            out[at] = s.id.toByte()
            out[at + 1] = s.type.toByte()
            out[at + 2] = s.version.toByte()
        }
        return out
    }

    /** Constants and the values this receiver advertises. */
    public companion object {
        private const val VERSION = 1
        private const val FIXED_BYTES = 10
        private const val SESSION_BYTES = 3

        /** Control session id (CSM messages). */
        public const val CONTROL_SESSION: Int = 10

        /** External accessory session id. */
        public const val EXTERNAL_ACCESSORY_SESSION: Int = 11

        /** File transfer session id. */
        public const val FILE_TRANSFER_SESSION: Int = 12

        /**
         * What we advertise over Bluetooth, byte for byte DiPlay's wireless SYN
         * `01 04 FFFF 0FA0 01F4 04 03 0A0002 0B0201 0C0102` (Iap2LinkChannel.kt:366-369, Iap2LinkEngine.kt:601-613).
         */
        public val WIRELESS: Iap2LinkParameters =
            Iap2LinkParameters(
                maxOutgoing = 4,
                maxFrameBytes = Iap2Frame.MAX_FRAME_BYTES,
                retransmitMillis = 4_000,
                acknowledgeMillis = 500,
                maxRetransmissions = 4,
                maxCumulativeAcknowledgements = 3,
                sessions =
                    listOf(
                        Iap2SessionDescriptor(CONTROL_SESSION, type = 0, version = 2),
                        Iap2SessionDescriptor(EXTERNAL_ACCESSORY_SESSION, type = 2, version = 1),
                        Iap2SessionDescriptor(FILE_TRANSFER_SESSION, type = 1, version = 2),
                    ),
            )

        /**
         * Parses a peer's SYN payload; null when it is too short or another version
         * (DiPlay Iap2LinkEngine.kt:85-104).
         */
        public fun decode(payload: ByteArray): Iap2LinkParameters? {
            if (payload.size < FIXED_BYTES || payload[0].toInt() != VERSION) return null
            val sessions =
                (FIXED_BYTES..payload.size - SESSION_BYTES step SESSION_BYTES).map {
                    Iap2SessionDescriptor(payload.u8(it), payload.u8(it + 1), payload.u8(it + 2))
                }
            return Iap2LinkParameters(
                maxOutgoing = payload.u8(1),
                maxFrameBytes = payload.u16(2),
                retransmitMillis = payload.u16(4),
                acknowledgeMillis = payload.u16(6),
                maxRetransmissions = payload.u8(8),
                maxCumulativeAcknowledgements = payload.u8(9),
                sessions = sessions,
            )
        }
    }
}

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

internal fun ByteArray.putU16(
    at: Int,
    value: Int,
) {
    this[at] = (value ushr 8).toByte()
    this[at + 1] = value.toByte()
}

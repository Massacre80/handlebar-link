package com.abid.handlebarlink.carplay.protocol.iap2

// Control-session (CSM) messages on link session 10 (DiPlay iap2/wire/Iap2CsmFramer.kt:6, :98-134,
// Iap2WireCodec.kt; spec B §1). Big-endian, no checksum:
//   message   = 40 40 | u16 length (whole message) | u16 id | parameter…
//   parameter = u16 length (incl. its 4-byte header) | u16 id | payload
// Strings are UTF-8 plus one NUL; a group's payload is itself a parameter list.

/** A control-session message. */
public class Iap2Message(
    /** The 16-bit message id, e.g. 0x1D01. */
    public val id: Int,
    /** Parameters in wire order; an id may repeat. */
    public val parameters: List<Iap2Parameter>,
    /** The body could not be parsed into parameters; [parameters] is then empty (see [Iap2ControlStream]). */
    public val malformed: Boolean = false,
) {
    /** The first parameter with [id], or null. */
    public fun parameter(id: Int): Iap2Parameter? = parameters.firstOrNull { it.id == id }

    /** The whole message, header included. */
    public fun encode(): ByteArray {
        val body = encodeParameters(parameters)
        val length = HEADER_BYTES + body.size
        require(length <= MAX_BYTES) { "iAP2 message 0x%04X of $length bytes is too long".format(id) }
        return ByteArray(length).also {
            it.putU16(0, START)
            it.putU16(2, length)
            it.putU16(4, id)
            body.copyInto(it, HEADER_BYTES)
        }
    }

    override fun toString(): String = "Iap2Message(0x%04X, %d parameters)".format(id, parameters.size)

    /** Framing constants. */
    public companion object {
        /** Start of every message. */
        public const val START: Int = 0x4040

        /** Start, length and id. */
        public const val HEADER_BYTES: Int = 6

        /** The length field is u16. */
        public const val MAX_BYTES: Int = 0xFFFF

        /**
         * Parses a message body (the bytes after the 6-byte header).
         *
         * @throws Iap2ProtocolException when a parameter overruns the body.
         */
        public fun decode(
            id: Int,
            body: ByteArray,
        ): Iap2Message = Iap2Message(id, decodeParameters(body))
    }
}

/** One parameter of a message or group. */
public class Iap2Parameter(
    /** The 16-bit parameter id. */
    public val id: Int,
    /** The payload, header excluded. */
    public val payload: ByteArray,
) {
    /** A NUL-terminated UTF-8 string; one with a NUL inside is refused (DiPlay PR #1297). */
    public fun string(): String {
        if (payload.isEmpty() ||
            payload.last() != 0.toByte()
        ) {
            throw Iap2ProtocolException("String $id not NUL terminated")
        }
        val text = payload.copyOf(payload.size - 1)
        if (text.contains(0.toByte())) throw Iap2ProtocolException("String $id contains a NUL")
        return text.decodeToString()
    }

    /** An unsigned byte. */
    public fun u8(): Int = fixed(1).u8(0)

    /** An unsigned big-endian 16-bit value. */
    public fun u16(): Int = fixed(2).u16(0)

    /** The payload as a list of big-endian u16 values (message id lists). */
    public fun u16List(): List<Int> {
        if (payload.size % 2 != 0) throw Iap2ProtocolException("u16 list $id has an odd length")
        return (payload.indices step 2).map { payload.u16(it) }
    }

    /** The parameters inside a group. */
    public fun group(): List<Iap2Parameter> = decodeParameters(payload)

    private fun fixed(size: Int): ByteArray {
        if (payload.size != size) throw Iap2ProtocolException("Parameter $id has ${payload.size} bytes, expected $size")
        return payload
    }

    override fun toString(): String = "Iap2Parameter($id, ${payload.size} bytes)"

    /** Size of a parameter's header. */
    public companion object {
        /** Length and id. */
        public const val HEADER_BYTES: Int = 4
    }
}

/** A malformed control-session message. */
public class Iap2ProtocolException(
    message: String,
) : Exception(message)

internal fun encodeParameters(parameters: List<Iap2Parameter>): ByteArray {
    val out = ByteArray(parameters.sumOf { Iap2Parameter.HEADER_BYTES + it.payload.size })
    var at = 0
    parameters.forEach {
        val length = Iap2Parameter.HEADER_BYTES + it.payload.size
        require(length <= Iap2Message.MAX_BYTES) { "iAP2 parameter ${it.id} too long" }
        out.putU16(at, length)
        out.putU16(at + 2, it.id)
        it.payload.copyInto(out, at + Iap2Parameter.HEADER_BYTES)
        at += length
    }
    return out
}

internal fun decodeParameters(bytes: ByteArray): List<Iap2Parameter> {
    val parameters = ArrayList<Iap2Parameter>()
    var at = 0
    while (at < bytes.size) {
        if (bytes.size - at < Iap2Parameter.HEADER_BYTES) throw Iap2ProtocolException("Truncated parameter header")
        val length = bytes.u16(at)
        if (length < Iap2Parameter.HEADER_BYTES || at + length > bytes.size) {
            throw Iap2ProtocolException("Parameter length $length overruns the message")
        }
        parameters += Iap2Parameter(bytes.u16(at + 2), bytes.copyOfRange(at + Iap2Parameter.HEADER_BYTES, at + length))
        at += length
    }
    return parameters
}

package com.abid.handlebarlink.carplay.protocol.iap2

/**
 * The control session's byte stream: one message may span several link payloads and one payload may hold
 * several messages. Resynchronises on `40 40` one byte at a time, as DiPlay does (Iap2CsmFramer.kt:35-52).
 * DiPlay's framer only cuts messages and their handlers parse the body, so a malformed body is never fatal
 * to the link (Iap2WirelessControlClientTest: a malformed 0x4300 is still answered). Here a body whose
 * parameters do not parse comes out as [Iap2Message.malformed] with no parameters.
 */
public class Iap2ControlStream {
    private var buffer = ByteArray(0)

    /** Adds a session-10 payload; returns the messages it completed. */
    public fun receive(payload: ByteArray): List<Iap2Message> {
        buffer += payload
        // A message is at most 64 KiB, so more than two of them buffered means the stream is broken.
        if (buffer.size > 2 * Iap2Message.MAX_BYTES) throw Iap2ProtocolException("Control stream buffer overflow")
        val messages = ArrayList<Iap2Message>()
        var at = 0
        var waiting = false
        while (!waiting) {
            while (buffer.size - at >= 2 && buffer.u16(at) != Iap2Message.START) at++
            val length = if (buffer.size - at >= Iap2Message.HEADER_BYTES) buffer.u16(at + 2) else -1
            when {
                length < 0 -> {
                    waiting = true
                }

                length < Iap2Message.HEADER_BYTES -> {
                    at++
                }

                buffer.size - at < length -> {
                    waiting = true
                }

                else -> {
                    messages +=
                        decodeOrMalformed(
                            buffer.u16(at + 4),
                            buffer.copyOfRange(
                                at + Iap2Message.HEADER_BYTES,
                                at + length,
                            ),
                        )
                    at += length
                }
            }
        }
        buffer = buffer.copyOfRange(at, buffer.size)
        return messages
    }

    private fun decodeOrMalformed(
        id: Int,
        body: ByteArray,
    ): Iap2Message =
        try {
            Iap2Message.decode(id, body)
        } catch (_: Iap2ProtocolException) {
            Iap2Message(id, emptyList(), malformed = true)
        }

    /** Splitting messages for the link. */
    public companion object {
        /** [message] in link payloads of at most [maxPayloadBytes] (DiPlay Iap2CsmFramer.kt:137-153). */
        public fun chunks(
            message: Iap2Message,
            maxPayloadBytes: Int,
        ): List<ByteArray> {
            require(maxPayloadBytes > 0) { "Link payload limit must be positive" }
            val bytes = message.encode()
            return (bytes.indices step maxPayloadBytes).map {
                bytes.copyOfRange(
                    it,
                    minOf(it + maxPayloadBytes, bytes.size),
                )
            }
        }
    }
}

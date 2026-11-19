package com.abid.handlebarlink.carplay.engine.runtime

import com.abid.handlebarlink.carplay.engine.spi.ByteStream
import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2ControlStream
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Message
import com.abid.handlebarlink.carplay.protocol.pairing.ControlCipher
import com.abid.handlebarlink.carplay.protocol.pairing.Tlv8
import java.security.SecureRandom

/**
 * The iPhone's iAP2 side over Bluetooth. Frames are built and parsed here independently of the protocol
 * module (spec A §3): FF 5A | length | control | seq | ack | session | checksum [payload | checksum].
 * By default it answers with zero-acknowledgement parameters, so neither side has to acknowledge data; with
 * [ACKING_PARAMETERS] it acknowledges every data packet it reads and expects the receiver to do the same.
 */
internal class PhoneIap2(
    private val stream: ByteStream,
    private val parameters: ByteArray = ZERO_ACK_PARAMETERS,
) {
    private val acking = parameters[CUMULATIVE_ACK_INDEX] != 0.toByte()

    private var buffer = ByteArray(0)
    private val control = Iap2ControlStream()
    private val messages = ArrayDeque<Iap2Message>()
    private var sequence = PHONE_FIRST_SEQUENCE
    private var lastReceived = 0

    /** Answers the marker and the SYN; returns once our ACK of its SYN+ACK arrived. */
    suspend fun handshake() {
        while (buffer.size < MARKER.size) fill()
        check(buffer.copyOf(MARKER.size).contentEquals(MARKER)) { "expected the iAP2 marker" }
        buffer = buffer.copyOfRange(MARKER.size, buffer.size)
        stream.write(MARKER)
        val syn = nextFrame()
        check(syn.control == SYN) { "expected SYN, got ${syn.control}" }
        lastReceived = syn.sequence
        stream.write(frame(SYN or ACK, sequence, syn.sequence, 0, parameters))
        val ack = nextFrame()
        check(ack.control == ACK && ack.payload == null) { "expected a pure ACK" }
    }

    /** Sends a control-session message; returns its sequence number. */
    suspend fun send(message: Iap2Message): Int {
        sequence = (sequence + 1) and 0xFF
        stream.write(frame(ACK, sequence, lastReceived, CONTROL_SESSION, message.encode()))
        return sequence
    }

    /** Sends a whole control-session message given as bytes (built outside our encoder); returns its sequence. */
    suspend fun sendRaw(message: ByteArray): Int {
        sequence = (sequence + 1) and 0xFF
        stream.write(frame(ACK, sequence, lastReceived, CONTROL_SESSION, message))
        return sequence
    }

    /** Reads until the receiver acknowledges [sequence] (any frame with the ACK bit carrying it). */
    suspend fun awaitAcknowledgement(sequence: Int) {
        while (true) {
            val next = nextFrame()
            accept(next)
            if (next.control and ACK != 0 && next.ack == sequence) return
        }
    }

    /** The next control-session message from the receiver. */
    suspend fun receive(): Iap2Message {
        while (messages.isEmpty()) accept(nextFrame())
        return messages.removeFirst()
    }

    /** Takes a frame's message (once; a retransmission is only acknowledged again) and acknowledges data. */
    private suspend fun accept(next: Frame) {
        val payload = next.payload ?: return
        val fresh = next.sequence != lastReceived
        if (fresh) lastReceived = next.sequence
        if (fresh && next.session == CONTROL_SESSION) messages.addAll(control.receive(payload))
        if (acking) stream.write(frame(ACK, sequence, lastReceived, 0, null))
    }

    /** Receives until a message with [id] arrives; returns it. */
    suspend fun expect(id: Int): Iap2Message {
        var message = receive()
        while (message.id != id) message = receive()
        return message
    }

    class Frame(
        val control: Int,
        val sequence: Int,
        val ack: Int,
        val session: Int,
        val payload: ByteArray?,
    )

    private suspend fun nextFrame(): Frame {
        while (buffer.size < HEADER) fill()
        val length = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        check(buffer[0] == 0xFF.toByte() && buffer[1] == 0x5A.toByte() && sum(buffer, 0, HEADER) == 0) { "bad header" }
        while (buffer.size < length) fill()
        val payload = if (length > HEADER) buffer.copyOfRange(HEADER, length - 1) else null
        if (payload != null) check(sum(buffer, HEADER, length - HEADER) == 0) { "bad payload checksum" }
        val frame =
            Frame(
                buffer[4].toInt() and 0xFF,
                buffer[5].toInt() and 0xFF,
                buffer[6].toInt() and 0xFF,
                buffer[7].toInt() and 0xFF,
                payload,
            )
        buffer = buffer.copyOfRange(length, buffer.size)
        return frame
    }

    private suspend fun fill() {
        val chunk = ByteArray(CHUNK)
        val count = stream.read(chunk)
        check(count >= 0) { "receiver closed Bluetooth" }
        buffer += chunk.copyOf(count)
    }

    companion object {
        const val SYN = 0x80
        const val ACK = 0x40
        const val HEADER = 9
        const val CONTROL_SESSION = 10
        const val PHONE_FIRST_SEQUENCE = 0x40
        const val CHUNK = 4096
        val MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)

        /** DiPlay's zero-acknowledgement SYN parameters (spec A §4, wired config). */
        val ZERO_ACK_PARAMETERS =
            byteArrayOf(1, 4, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 10, 0, 2, 11, 2, 1, 12, 1, 2)

        /**
         * DiPlay's wireless SYN `01 04 FFFF 0FA0 01F4 04 03 …` (spec A §4): 4 s retransmit, 500 ms acknowledgement
         * timeout, 4 retransmissions, 3 cumulative acknowledgements.
         */
        val ACKING_PARAMETERS =
            byteArrayOf(1, 4, 0xFF.toByte(), 0xFF.toByte(), 0x0F, 0xA0.toByte(), 0x01, 0xF4.toByte(), 4, 3) +
                byteArrayOf(10, 0, 2, 11, 2, 1, 12, 1, 2)

        /** Where the SYN payload holds the cumulative acknowledgement count. */
        private const val CUMULATIVE_ACK_INDEX = 9

        private fun sum(
            bytes: ByteArray,
            from: Int,
            count: Int,
        ): Int = (from until from + count).sumOf { bytes[it].toInt() and 0xFF } and 0xFF

        fun frame(
            control: Int,
            sequence: Int,
            ack: Int,
            session: Int,
            payload: ByteArray?,
        ): ByteArray {
            val length = HEADER + (payload?.let { it.size + 1 } ?: 0)
            val header =
                byteArrayOf(
                    0xFF.toByte(),
                    0x5A,
                    (length shr 8).toByte(),
                    length.toByte(),
                    control.toByte(),
                    sequence.toByte(),
                    ack.toByte(),
                    session.toByte(),
                )
            val withChecksum = header + (-sum(header, 0, header.size)).toByte()
            return if (payload ==
                null
            ) {
                withChecksum
            } else {
                withChecksum + payload + (-sum(payload, 0, payload.size)).toByte()
            }
        }
    }
}

/** A parsed RTSP response. */
internal class PhoneResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** The iPhone's AirPlay control connection: RTSP requests, pair-verify, then the encrypted channel. */
internal class PhoneAirPlay(
    private val stream: ByteStream,
    private val random: SecureRandom,
) {
    private var cseq = 0
    private var cipher: ControlCipher? = null
    private var pending = ByteArray(0)

    /** Pair-verify's X25519 secret, computed on the iPhone's side, once verified. */
    var sharedSecret: ByteArray? = null
        private set

    suspend fun request(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        trailing: ByteArray = ByteArray(0),
        contentType: String? = null,
    ): PhoneResponse {
        cseq++
        val type = contentType?.let { "Content-Type: $it\r\n" }.orEmpty()
        val head = "$method $path RTSP/1.0\r\nCSeq: $cseq\r\n${type}Content-Length: ${body.size}\r\n\r\n"
        val wire = head.encodeToByteArray() + body + trailing
        stream.write(cipher?.encrypt(wire) ?: wire)
        return readResponse()
    }

    /**
     * Pair-verify as a phone that completed pair-setup with [keys] as [id]; then encrypts like the iPhone.
     * [trailing] bytes go out in the same write as M3.
     */
    suspend fun pairVerify(
        id: String,
        keys: Ed25519KeyPair,
        trailing: ByteArray = ByteArray(0),
    ): Boolean {
        val ephemeral = X25519KeyPair.generate(random)
        val m2 =
            Tlv8.decode(
                request(
                    "POST",
                    "/pair-verify",
                    Tlv8.encode(
                        Tlv8.STATE to byteArrayOf(1),
                        Tlv8.PUBLIC_KEY to ephemeral.publicKey,
                    ),
                ).body,
            )
        val accessoryKey = m2.getValue(Tlv8.PUBLIC_KEY)
        val shared = ephemeral.agree(accessoryKey)
        val aead =
            ChaCha20Poly1305(Hkdf.sha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", 32), CIPHER)
        val proof = keys.sign(ephemeral.publicKey + id.encodeToByteArray() + accessoryKey)
        val inner = Tlv8.encode(Tlv8.IDENTIFIER to id.encodeToByteArray(), Tlv8.SIGNATURE to proof)
        val sealed = aead.seal(ByteArray(4) + "PV-Msg03".encodeToByteArray(), ByteArray(0), inner)
        val response =
            request(
                "POST",
                "/pair-verify",
                Tlv8.encode(Tlv8.STATE to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to sealed),
                trailing,
            )
        val ok = response.status == 200 && Tlv8.decode(response.body)[Tlv8.ERROR] == null
        if (ok) {
            sharedSecret = shared
            cipher =
                ControlCipher(
                    readKey = Hkdf.sha512(shared, "Control-Salt", "Control-Read-Encryption-Key", 32),
                    writeKey = Hkdf.sha512(shared, "Control-Salt", "Control-Write-Encryption-Key", 32),
                    cipherName = CIPHER,
                )
        }
        return ok
    }

    fun close() = stream.close()

    private suspend fun readResponse(): PhoneResponse {
        val message =
            readMessage(stream, { pending }, { pending = it }) { bytes, count -> cipher?.decrypt(bytes, 0, count) }
        return PhoneResponse(message.startLine.split(' ')[1].toInt(), message.headers, message.body)
    }

    companion object {
        const val CIPHER = ChaCha20Poly1305.Transformation.JVM
    }
}

/** One RTSP message as the iPhone reads it: the first line, headers by lower-case name, the body and the raw head. */
internal class PhoneMessage(
    val startLine: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val head: String,
)

/** Reads one RTSP message (request or response) from [stream], decrypting with [decrypt] when it returns bytes. */
internal suspend fun readMessage(
    stream: ByteStream,
    pending: () -> ByteArray,
    setPending: (ByteArray) -> Unit,
    decrypt: (ByteArray, Int) -> ByteArray?,
): PhoneMessage {
    while (true) {
        val buffered = pending()
        val end =
            (0..buffered.size - 4).firstOrNull {
                buffered[it] == '\r'.code.toByte() && buffered[it + 1] == '\n'.code.toByte() &&
                    buffered[it + 2] == '\r'.code.toByte() && buffered[it + 3] == '\n'.code.toByte()
            } ?: -1
        if (end >= 0) {
            val head = buffered.copyOf(end).decodeToString()
            val lines = head.split("\r\n")
            val headers =
                lines
                    .drop(
                        1,
                    ).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val length = headers["content-length"]?.toInt() ?: 0
            if (buffered.size >= end + 4 + length) {
                val body = buffered.copyOfRange(end + 4, end + 4 + length)
                setPending(buffered.copyOfRange(end + 4 + length, buffered.size))
                return PhoneMessage(lines[0], headers, body, head)
            }
        }
        val chunk = ByteArray(PhoneIap2.CHUNK)
        val count = stream.read(chunk)
        check(count >= 0) { "receiver closed the connection" }
        setPending(buffered + (decrypt(chunk, count) ?: chunk.copyOf(count)))
    }
}

/**
 * The iPhone's end of the event channel. Its keys are derived here from the iPhone's own shared secret with the labels
 * as the iPhone uses them (spec C §5): it writes with "Events-Read-Encryption-Key", the receiver's read key.
 */
internal class PhoneEvents(
    private val stream: ByteStream,
    sharedSecret: ByteArray,
) {
    private val cipher =
        ControlCipher(
            readKey = Hkdf.sha512(sharedSecret, "Events-Salt", "Events-Write-Encryption-Key", 32),
            writeKey = Hkdf.sha512(sharedSecret, "Events-Salt", "Events-Read-Encryption-Key", 32),
            cipherName = PhoneAirPlay.CIPHER,
        )
    private var pending = ByteArray(0)

    /** Sends a request on the event channel. */
    suspend fun send(head: String) = stream.write(cipher.encrypt(head.encodeToByteArray()))

    /** Sends raw bytes (e.g. a frame sealed with the wrong key). */
    suspend fun sendRaw(bytes: ByteArray) = stream.write(bytes)

    /** The next message from the receiver. */
    suspend fun read(): PhoneMessage =
        readMessage(stream, {
            pending
        }, { pending = it }) { bytes, count -> cipher.decrypt(bytes, 0, count) }

    fun close() = stream.close()
}

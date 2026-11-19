package com.abid.handlebarlink.carplay.protocol.stream

import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import java.io.ByteArrayOutputStream

/**
 * What the iPhone sends on the iAP2 tunnel's TCP data connection (stream type 130, `clientTypeUUID`
 * [CLIENT_TYPE_UUID]), as DiPlay reads it in `IapTunnel` (IapTunnel.kt:137-205; spec D §1.5, §5.2):
 *
 * - Frames of `u16 LE length | ciphertext | 16-byte tag`, the two length bytes as AAD, ChaCha20-Poly1305 with the
 *   stream's output key ([StreamKeys.output] with the SETUP's `seed`) and nonce = 4 zero bytes + LE64 counter from 0
 *   (IapTunnel.kt:170-187; `AirPlayCrypto.nonce64`, AirPlayCrypto.kt:220-228).
 * - The plaintext is a stream of APTransportPackage records: a 32-byte header with the record's whole size (header
 *   included) as a big-endian u32 at offset 0 and its message type as a big-endian u32 at offset 16. Only `comm`
 *   records carry iAP2 bytes, the body after the header, handed on verbatim; other types are skipped
 *   (IapTunnel.kt:189-205).
 *
 * Hostile-input limits are DiPlay's: a record smaller than its header or larger than [MAX_PACKAGE_BYTES] ends the
 * tunnel (IapTunnel.kt:193); a frame is at most 65 535 bytes by its length field. An empty `comm` body is dropped,
 * as DiPlay's bridge ignores it (AirPlayIapTunnelStream.kt:107). We only read here: what we send to the iPhone goes
 * as `iAPSendMessage` commands on the event channel (AirPlayIapTunnelStream.kt:8-13, AirPlaySession.kt:395-427).
 *
 * One reader per TCP connection: a new connection starts the counter at 0 again (IapTunnel.kt:130). Not thread-safe.
 */
public class IapTunnelReader(
    readKey: ByteArray,
    cipherName: String,
) {
    private val cipher = ChaCha20Poly1305(readKey, cipherName)
    private var counter = 0L
    private var ciphertext = ByteArray(0)
    private var plaintext = ByteArray(0)

    /**
     * Adds [length] bytes read from the connection; returns the iAP2 bytes of every `comm` record they completed, in
     * order.
     *
     * @throws javax.crypto.AEADBadTagException when a frame does not authenticate.
     * @throws IapTunnelException when a record's size is impossible.
     */
    public fun receive(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): List<ByteArray> {
        ciphertext += bytes.copyOfRange(offset, offset + length)
        plaintext += decryptFrames()
        return packages()
    }

    private fun decryptFrames(): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        while (ciphertext.size - at >= FRAME_HEADER_BYTES) {
            val frameLength = (ciphertext[at].toInt() and BYTE) or ((ciphertext[at + 1].toInt() and BYTE) shl BITS)
            val end = at + FRAME_HEADER_BYTES + frameLength + ChaCha20Poly1305.TAG_BYTES
            if (ciphertext.size < end) break
            val header = ciphertext.copyOfRange(at, at + FRAME_HEADER_BYTES)
            out.write(
                cipher.open(
                    nonce(counter),
                    header,
                    ciphertext,
                    at + FRAME_HEADER_BYTES,
                    end - at - FRAME_HEADER_BYTES,
                ),
            )
            counter++
            at = end
        }
        ciphertext = ciphertext.copyOfRange(at, ciphertext.size)
        return out.toByteArray()
    }

    private fun packages(): List<ByteArray> {
        val bodies = ArrayList<ByteArray>()
        var at = 0
        while (plaintext.size - at >= PACKAGE_HEADER_BYTES) {
            val size = u32(plaintext, at)
            if (size < PACKAGE_HEADER_BYTES ||
                size > MAX_PACKAGE_BYTES
            ) {
                throw IapTunnelException("bad package size $size")
            }
            if (plaintext.size - at < size) break
            if (u32(plaintext, at + MESSAGE_TYPE_OFFSET) == COMM && size > PACKAGE_HEADER_BYTES) {
                bodies += plaintext.copyOfRange(at + PACKAGE_HEADER_BYTES, at + size.toInt())
            }
            at += size.toInt()
        }
        plaintext = plaintext.copyOfRange(at, plaintext.size)
        return bodies
    }

    /** The tunnel's identifiers and DiPlay's limits. */
    public companion object {
        /** The type-130 data stream that carries iAP2 (CarPlayMediaEngine.kt:707). */
        public const val CLIENT_TYPE_UUID: String = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"

        /** DiPlay's record cap (IapTunnel.kt:222). */
        public const val MAX_PACKAGE_BYTES: Long = 4L * 1024 * 1024

        /** Record header length (IapTunnel.kt:219). */
        public const val PACKAGE_HEADER_BYTES: Int = 32

        private const val FRAME_HEADER_BYTES = 2
        private const val MESSAGE_TYPE_OFFSET = 16

        /** "comm" in ASCII (IapTunnel.kt:221). */
        private const val COMM = 0x636f6d6dL
        private const val BYTE = 0xFF
        private const val BITS = 8
        private const val NONCE_BYTES = 12
        private const val COUNTER_AT = 4

        /** 4 zero bytes and the little-endian counter (AirPlayCrypto.kt:220-228). */
        private fun nonce(counter: Long): ByteArray {
            val nonce = ByteArray(NONCE_BYTES)
            var value = counter
            for (index in COUNTER_AT until NONCE_BYTES) {
                nonce[index] = value.toByte()
                value = value ushr BITS
            }
            return nonce
        }

        /** Unsigned, so a size above 2^31 is rejected as too large rather than read as negative. */
        private fun u32(
            bytes: ByteArray,
            at: Int,
        ): Long =
            (0 until Int.SIZE_BYTES).fold(0L) { value, i -> (value shl BITS) or (bytes[at + i].toLong() and 0xFF) }
    }
}

/** The tunnel's byte stream is broken; the tunnel ends. */
public class IapTunnelException(
    message: String,
) : Exception(message)

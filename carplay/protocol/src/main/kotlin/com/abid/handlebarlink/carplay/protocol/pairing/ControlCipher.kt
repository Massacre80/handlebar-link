package com.abid.handlebarlink.carplay.protocol.pairing

import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import java.io.ByteArrayOutputStream

/**
 * The encrypted AirPlay control (and event) channel after pair-verify, as DiPlay's ControlCipher.kt
 * (spec C §5): frames of `u16 LE length | ciphertext | 16-byte tag`, the two length bytes as AAD, nonce =
 * 4 zero bytes + LE64 counter, one counter per direction from 0, at most 16 KiB of plaintext per frame.
 *
 * Frame boundaries are independent of RTSP message boundaries. Not thread-safe: the read side and the
 * write side may each be used by one thread.
 */
public class ControlCipher(
    readKey: ByteArray,
    writeKey: ByteArray,
    cipherName: String,
) {
    private val reader = ChaCha20Poly1305(readKey, cipherName)
    private val writer = ChaCha20Poly1305(writeKey, cipherName)
    private var readCounter = 0L
    private var writeCounter = 0L
    private var pending = ByteArray(0)

    /** Encrypts [plaintext] into one or more frames. */
    public fun encrypt(plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plaintext.size + (plaintext.size / MAX_PAYLOAD + 1) * OVERHEAD)
        var offset = 0
        do {
            val length = minOf(MAX_PAYLOAD, plaintext.size - offset)
            val header = byteArrayOf(length.toByte(), (length ushr 8).toByte())
            out.write(header)
            out.write(writer.seal(counterNonce(writeCounter++), header, plaintext.copyOfRange(offset, offset + length)))
            offset += length
        } while (offset < plaintext.size)
        return out.toByteArray()
    }

    /**
     * Adds bytes read from the socket; returns the plaintext of every frame they completed.
     *
     * @throws javax.crypto.AEADBadTagException when a frame does not authenticate; the channel is then unusable.
     */
    public fun decrypt(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        pending += bytes.copyOfRange(offset, offset + length)
        val out = ByteArrayOutputStream()
        var at = 0
        while (pending.size - at >= HEADER_BYTES) {
            val frameLength = (pending[at].toInt() and 0xFF) or ((pending[at + 1].toInt() and 0xFF) shl 8)
            val end = at + HEADER_BYTES + frameLength + ChaCha20Poly1305.TAG_BYTES
            if (pending.size < end) break
            val header = pending.copyOfRange(at, at + HEADER_BYTES)
            out.write(
                reader.open(
                    counterNonce(readCounter++),
                    header,
                    pending,
                    at + HEADER_BYTES,
                    end - at - HEADER_BYTES,
                ),
            )
            at = end
        }
        pending = pending.copyOfRange(at, pending.size)
        return out.toByteArray()
    }

    /** Key names for the two channels. */
    public companion object {
        private const val HEADER_BYTES = 2
        private const val OVERHEAD = HEADER_BYTES + ChaCha20Poly1305.TAG_BYTES

        /** DiPlay's ControlCipher.kt:53. */
        public const val MAX_PAYLOAD: Int = 0x4000

        /**
         * The event channel's cipher. Its key names are the opposite of the control channel's: we read with
         * "Events-Read-Encryption-Key" (DiPlay AirPlaySession.kt:897-916, spec C §5, §10.6).
         */
        public fun events(
            sharedSecret: ByteArray,
            cipherName: String,
        ): ControlCipher =
            ControlCipher(
                readKey = Hkdf.sha512(sharedSecret, "Events-Salt", "Events-Read-Encryption-Key", 32),
                writeKey = Hkdf.sha512(sharedSecret, "Events-Salt", "Events-Write-Encryption-Key", 32),
                cipherName = cipherName,
            )
    }
}

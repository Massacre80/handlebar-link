package com.abid.handlebarlink.carplay.protocol.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) on the platform's HMAC, which is native on Android (Conscrypt/BoringSSL) and the JDK.
 * AirPlay derives every session key with HKDF-SHA-512 and ASCII salt and info strings.
 */
public object Hkdf {
    /** HMAC algorithm names accepted by [derive]. */
    public const val SHA256: String = "HmacSHA256"

    /** HKDF-SHA-512, the hash AirPlay pairing uses. */
    public const val SHA512: String = "HmacSHA512"

    /** HKDF-SHA-512 of [ikm] with ASCII [salt] and [info], [length] bytes long. */
    public fun sha512(
        ikm: ByteArray,
        salt: String,
        info: String,
        length: Int,
    ): ByteArray = derive(SHA512, ikm, salt.encodeToByteArray(), info.encodeToByteArray(), length)

    /** RFC 5869 extract-then-expand with [hmac] ([SHA256] or [SHA512]). */
    public fun derive(
        hmac: String,
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val mac = Mac.getInstance(hmac)
        val hashLength = mac.macLength
        require(length in 1..MAX_BLOCKS * hashLength) { "HKDF length $length out of range" }
        // Extract: an empty salt means hashLength zero bytes (RFC 5869 §2.2).
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(hashLength) else salt, hmac))
        val prk = mac.doFinal(ikm)
        // Expand: T(i) = HMAC(PRK, T(i-1) | info | i).
        mac.init(SecretKeySpec(prk, hmac))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }

    private const val MAX_BLOCKS = 255
}

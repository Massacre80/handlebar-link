package com.abid.handlebarlink.carplay.protocol.pairing

import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `POST /auth-setup` (MFi-SAP) on the encrypted control channel, as DiPlay's MfiSapAuthSetup.kt:20-82
 * (spec C §6). Request: `01 | iPhone X25519 key (32)`. Reply:
 * `our X25519 key (32) | u32 BE cert length | certificate | u32 BE signature length | AES-128-CTR(signature)`
 * where the MFi key signs SHA-256(ourKey | phoneKey) (protocol 3, as the offline identity reports), and
 * the AES key and IV are SHA-1("AES-KEY" | shared)[0..16] and SHA-1("AES-IV" | shared)[0..16].
 */
public object AuthSetup {
    private const val VERSION = 0x01
    private const val KEY_BYTES = 32
    private const val AES_BYTES = 16

    /** The reply body, or null for a malformed request (answered with 400). */
    public fun handle(
        body: ByteArray,
        mfi: MfiIdentity,
        random: SecureRandom,
    ): ByteArray? {
        val phoneKey = body.takeIf { it.size == 1 + KEY_BYTES && it[0].toInt() == VERSION }?.copyOfRange(1, body.size)
        val ours = X25519KeyPair.generate(random)
        val shared = phoneKey?.let { agreeOrNull(ours, it) }
        return if (phoneKey == null || shared == null) null else reply(ours, phoneKey, shared, mfi)
    }

    /** X25519 with the iPhone's key; null for a small-order key. */
    private fun agreeOrNull(
        ours: X25519KeyPair,
        phoneKey: ByteArray,
    ): ByteArray? =
        try {
            ours.agree(phoneKey)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun reply(
        ours: X25519KeyPair,
        phoneKey: ByteArray,
        shared: ByteArray,
        mfi: MfiIdentity,
    ): ByteArray {
        val aesKey = digest("SHA-1", "AES-KEY".encodeToByteArray(), shared).copyOf(AES_BYTES)
        val aesIv = digest("SHA-1", "AES-IV".encodeToByteArray(), shared).copyOf(AES_BYTES)
        val signature = mfi.sign(digest("SHA-256", ours.publicKey, phoneKey))
        val encrypted =
            Cipher.getInstance("AES/CTR/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
                doFinal(signature)
            }
        val certificate = mfi.certificate
        return ours.publicKey + u32(certificate.size) + certificate + u32(encrypted.size) + encrypted
    }

    private fun digest(
        algorithm: String,
        vararg parts: ByteArray,
    ): ByteArray =
        MessageDigest.getInstance(algorithm).run {
            parts.forEach(::update)
            digest()
        }

    private fun u32(value: Int) =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}

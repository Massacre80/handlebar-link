package com.abid.handlebarlink.carplay.protocol.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439) on the platform's JCA provider: BoringSSL through Conscrypt on
 * Android (API 28+, so native on every supported release), the JDK's intrinsic on the JVM. Every
 * AirPlay channel (control, pairing, video, audio) uses it; the media streams run it per frame, so it
 * must stay native.
 *
 * The two platforms name the same cipher differently; the caller passes [Transformation.ANDROID] or
 * [Transformation.JVM]. Not thread-safe: use one instance per direction of a channel.
 */
public class ChaCha20Poly1305(
    key: ByteArray,
    transformation: String,
) {
    init {
        require(key.size == KEY_BYTES) { "ChaCha20-Poly1305 key must be $KEY_BYTES bytes, got ${key.size}" }
    }

    private val keySpec = SecretKeySpec(key, "ChaCha20")
    private val cipher = Cipher.getInstance(transformation)

    /** Encrypts [plaintext]; returns ciphertext followed by the 16-byte tag. */
    public fun seal(
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        init(Cipher.ENCRYPT_MODE, nonce, aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts ciphertext-and-tag [input] and checks the tag.
     *
     * @throws javax.crypto.AEADBadTagException when the tag does not verify.
     */
    public fun open(
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
    ): ByteArray = open(nonce, aad, input, 0, input.size)

    /**
     * Decrypts [length] bytes of ciphertext-and-tag from [input] at [offset] without copying it first;
     * returns the plaintext.
     *
     * @throws javax.crypto.AEADBadTagException when the tag does not verify.
     */
    public fun open(
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        require(length >= TAG_BYTES) { "Ciphertext shorter than the tag" }
        init(Cipher.DECRYPT_MODE, nonce, aad)
        return cipher.doFinal(input, offset, length)
    }

    /**
     * Decrypts the first [length] bytes of ciphertext-and-tag in [input] into the start of [output], which must have
     * room for [length] bytes (a provider may ask for the tag's room too); returns the plaintext's length. No array is
     * allocated: the media streams decrypt into reused buffers.
     *
     * @throws javax.crypto.AEADBadTagException when the tag does not verify.
     */
    public fun openInto(
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
        length: Int,
        output: ByteArray,
    ): Int {
        require(length >= TAG_BYTES) { "Ciphertext shorter than the tag" }
        require(output.size >= length) { "No room for the plaintext" }
        init(Cipher.DECRYPT_MODE, nonce, aad)
        return cipher.doFinal(input, 0, length, output, 0)
    }

    private fun init(
        mode: Int,
        nonce: ByteArray,
        aad: ByteArray,
    ) {
        require(nonce.size == NONCE_BYTES) { "Nonce must be $NONCE_BYTES bytes, got ${nonce.size}" }
        cipher.init(mode, keySpec, IvParameterSpec(nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
    }

    /** The platform names of the cipher. */
    public object Transformation {
        /** Conscrypt's name (Android API 28+). */
        public const val ANDROID: String = "ChaCha20/Poly1305/NoPadding"

        /** The JDK's name (Java 11+), used by the JVM tests. */
        public const val JVM: String = "ChaCha20-Poly1305"
    }

    /** Sizes fixed by RFC 8439. */
    public companion object {
        /** Key length. */
        public const val KEY_BYTES: Int = 32

        /** Nonce length. */
        public const val NONCE_BYTES: Int = 12

        /** Authentication tag length. */
        public const val TAG_BYTES: Int = 16
    }
}

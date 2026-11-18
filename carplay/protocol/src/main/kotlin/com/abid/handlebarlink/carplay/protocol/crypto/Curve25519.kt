package com.abid.handlebarlink.carplay.protocol.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

// X25519 and Ed25519 come from BouncyCastle: Android's own (KeyAgreement "XDH", Signature "Ed25519")
// starts at API 33 and we support API 31 (AGENTS.md). Both run once per pairing, never per frame.

/** An Ed25519 key pair (RFC 8032): the receiver's long-term AirPlay identity. */
public class Ed25519KeyPair private constructor(
    private val privateKey: Ed25519PrivateKeyParameters,
) {
    /** The 32-byte public key. */
    public val publicKey: ByteArray = privateKey.generatePublicKey().encoded

    /** The 32-byte private seed, for persisting the identity. */
    public val seed: ByteArray get() = privateKey.encoded

    /** Signs [message]; returns the 64-byte signature. */
    public fun sign(message: ByteArray): ByteArray =
        Ed25519Signer().run {
            init(true, privateKey)
            update(message, 0, message.size)
            generateSignature()
        }

    /** Creating key pairs. */
    public companion object {
        /** A new random key pair. */
        public fun generate(random: SecureRandom): Ed25519KeyPair = Ed25519KeyPair(Ed25519PrivateKeyParameters(random))

        /** The key pair of a stored 32-byte [seed]. */
        public fun fromSeed(seed: ByteArray): Ed25519KeyPair {
            require(seed.size == KEY_BYTES) { "Ed25519 seed must be $KEY_BYTES bytes, got ${seed.size}" }
            return Ed25519KeyPair(Ed25519PrivateKeyParameters(seed, 0))
        }

        /** Whether [signature] over [message] verifies with the 32-byte [publicKey]. */
        public fun verify(
            publicKey: ByteArray,
            message: ByteArray,
            signature: ByteArray,
        ): Boolean {
            if (publicKey.size != KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
            return Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(message, 0, message.size)
                verifySignature(signature)
            }
        }

        /** Key length. */
        public const val KEY_BYTES: Int = 32

        /** Signature length. */
        public const val SIGNATURE_BYTES: Int = 64
    }
}

/** An X25519 key pair (RFC 7748) for one pair-verify exchange. */
public class X25519KeyPair private constructor(
    private val privateKey: X25519PrivateKeyParameters,
) {
    /** The 32-byte public key. */
    public val publicKey: ByteArray = privateKey.generatePublicKey().encoded

    /**
     * The 32-byte shared secret with [peerPublicKey].
     *
     * @throws IllegalArgumentException when the peer key has the wrong size or is a small-order point
     *   (the all-zero result RFC 7748 §6.1 says to reject).
     */
    public fun agree(peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == KEY_BYTES) { "X25519 public key must be $KEY_BYTES bytes" }
        val shared = ByteArray(KEY_BYTES)
        try {
            privateKey.generateSecret(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
        } catch (e: IllegalStateException) {
            // BouncyCastle's own all-zero check (RFC 7748 §6.1); one exception type for callers.
            throw IllegalArgumentException("X25519 shared secret is all zero", e)
        }
        return shared
    }

    /** Creating key pairs. */
    public companion object {
        /** A new random key pair. */
        public fun generate(random: SecureRandom): X25519KeyPair = X25519KeyPair(X25519PrivateKeyParameters(random))

        /** The key pair of a fixed 32-byte private key (tests). */
        public fun fromPrivate(privateKey: ByteArray): X25519KeyPair {
            require(privateKey.size == KEY_BYTES) { "X25519 private key must be $KEY_BYTES bytes" }
            return X25519KeyPair(X25519PrivateKeyParameters(privateKey, 0))
        }

        /** Key length. */
        public const val KEY_BYTES: Int = 32
    }
}

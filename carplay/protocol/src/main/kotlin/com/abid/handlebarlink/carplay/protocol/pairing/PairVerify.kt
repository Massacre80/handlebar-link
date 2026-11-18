package com.abid.handlebarlink.carplay.protocol.pairing

import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import java.security.SecureRandom

/** The control channel's keys after pair-verify, from our side. */
public class ControlKeys(
    /** Decrypts what the iPhone sends ("Control-Write-Encryption-Key"). */
    public val readKey: ByteArray,
    /** Encrypts what we send ("Control-Read-Encryption-Key"). */
    public val writeKey: ByteArray,
)

/**
 * `POST /pair-verify` M1→M4, as DiPlay's PairVerify.kt (spec C §4) with CarLink's port of DiPlay PR #1124:
 * the X25519 secret is only published once the iPhone has proven its long-term key in M3. We sign
 * `ourEphemeral | ourPairingId | phoneEphemeral`; the iPhone signs `phoneEphemeral | phoneId | ourEphemeral`.
 * The M4 reply is sent in clear; encryption with [controlKeys] starts after it.
 *
 * An all-zero X25519 secret is refused, as in DiPlay (BouncyCastle's generateSecret throws; spec C §10.3 is wrong
 * on this). A new M1 replaces only the handshake: the verified secret and keys stay until a new M4 succeeds,
 * as DiPlay's PairVerify.kt:51 (later channels derive their keys from [sharedSecret]).
 * One instance per control connection; not thread-safe.
 */
public class PairVerify(
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val random: SecureRandom,
    private val cipherName: String,
) {
    private class Handshake(
        val ours: X25519KeyPair,
        val phoneKey: ByteArray,
        val secret: ByteArray,
    )

    private var handshake: Handshake? = null

    /** The verified X25519 secret, the source of every later key; null until M4 succeeded. */
    public var sharedSecret: ByteArray? = null
        private set

    /** The control channel keys; null until M4 succeeded. */
    public var controlKeys: ControlKeys? = null
        private set

    /** The iPhone's pairing id once verified. */
    public var controllerId: String? = null
        private set

    /** Handles one request body; returns the reply body. */
    public fun handle(body: ByteArray): ByteArray =
        handlePairing(body) { state, tlv ->
            when (state) {
                1 -> m2(tlv)
                3 -> m4(tlv)
                else -> throw PairingFailure(state)
            }
        }

    private fun m2(tlv: Map<Int, ByteArray>): ByteArray {
        val phoneKey = tlv[Tlv8.PUBLIC_KEY].orFail(2)
        handshake = null
        val ours = X25519KeyPair.generate(random)
        val shared = ours.agree(phoneKey)
        handshake = Handshake(ours, phoneKey.copyOf(), shared)
        val accessoryId = identity.pairingId.encodeToByteArray()
        val signature = identity.keys.sign(ours.publicKey + accessoryId + phoneKey)
        val sub = Tlv8.encode(Tlv8.IDENTIFIER to accessoryId, Tlv8.SIGNATURE to signature)
        val sealed = aead(shared).seal(labelNonce(MSG02), ByteArray(0), sub)
        return Tlv8.encode(
            Tlv8.STATE to byteArrayOf(2),
            Tlv8.PUBLIC_KEY to ours.publicKey,
            Tlv8.ENCRYPTED_DATA to sealed,
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray {
        val started = handshake.orFail(4)
        val sealed = tlv[Tlv8.ENCRYPTED_DATA].orFail(4)
        val sub = Tlv8.decode(aead(started.secret).open(labelNonce(MSG03), ByteArray(0), sealed))
        val phoneId = sub[Tlv8.IDENTIFIER].orFail(4)
        // Unknown iPhone: it runs pair-setup again (spec C §10.4).
        val longTermKey = pairings.get(phoneId.decodeToString()).orFail(4)
        val signed = started.phoneKey + phoneId + started.ours.publicKey
        if (!Ed25519KeyPair.verify(longTermKey, signed, sub[Tlv8.SIGNATURE].orFail(4))) throw PairingFailure(4)
        controlKeys =
            ControlKeys(
                readKey = Hkdf.sha512(started.secret, CONTROL_SALT, CONTROL_WRITE_KEY, KEY_BYTES),
                writeKey = Hkdf.sha512(started.secret, CONTROL_SALT, CONTROL_READ_KEY, KEY_BYTES),
            )
        sharedSecret = started.secret
        controllerId = phoneId.decodeToString()
        return Tlv8.encode(Tlv8.STATE to byteArrayOf(4))
    }

    private fun aead(shared: ByteArray) =
        ChaCha20Poly1305(Hkdf.sha512(shared, VERIFY_SALT, VERIFY_INFO, KEY_BYTES), cipherName)

    private companion object {
        const val KEY_BYTES = 32
        const val VERIFY_SALT = "Pair-Verify-Encrypt-Salt"
        const val VERIFY_INFO = "Pair-Verify-Encrypt-Info"
        const val CONTROL_SALT = "Control-Salt"
        const val CONTROL_READ_KEY = "Control-Read-Encryption-Key"
        const val CONTROL_WRITE_KEY = "Control-Write-Encryption-Key"
        const val MSG02 = "PV-Msg02"
        const val MSG03 = "PV-Msg03"
    }
}

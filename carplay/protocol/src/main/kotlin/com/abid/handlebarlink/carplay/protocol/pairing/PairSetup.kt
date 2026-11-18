package com.abid.handlebarlink.carplay.protocol.pairing

import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.Srp6aServer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * `POST /pair-setup`: full SRP pair-setup M1→M6 with CarPlay's fixed setup code, as DiPlay's
 * PairSetup.kt:19-136 (spec C §2). M2 = State, PublicKey(B), Salt; M4 = State, Proof(M2); M6 seals our
 * identifier, Ed25519 key and signature. The iPhone's long-term key goes to [pairings]. Every reply is a
 * TLV8 body for a 200 response; failures are `State, Error=2` (Authentication).
 *
 * One instance per control connection; not thread-safe.
 */
public class PairSetup(
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val random: SecureRandom,
    private val cipherName: String,
) {
    private var srp: Srp6aServer? = null
    private var sessionKey: ByteArray? = null

    /** Handles one request body; returns the reply body. */
    public fun handle(body: ByteArray): ByteArray =
        handlePairing(body) { state, tlv ->
            when (state) {
                1 -> m2()
                3 -> m4(tlv)
                5 -> m6(tlv)
                else -> throw PairingFailure(state)
            }
        }

    private fun m2(): ByteArray {
        val server = Srp6aServer(SETUP_CODE, random).also { srp = it }
        sessionKey = null
        val challenge = server.challenge
        return Tlv8.encode(
            Tlv8.STATE to byteArrayOf(2),
            Tlv8.PUBLIC_KEY to challenge.publicKey,
            Tlv8.SALT to challenge.salt,
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray {
        val server = srp.orFail(4)
        val result = server.verify(tlv[Tlv8.PUBLIC_KEY].orFail(4), tlv[Tlv8.PROOF].orFail(4)).orFail(4)
        sessionKey = result.sessionKey
        return Tlv8.encode(Tlv8.STATE to byteArrayOf(4), Tlv8.PROOF to result.serverProof)
    }

    private fun m6(tlv: Map<Int, ByteArray>): ByteArray {
        val key = sessionKey.orFail(6)
        val aead = ChaCha20Poly1305(Hkdf.sha512(key, ENCRYPT_SALT, ENCRYPT_INFO, KEY_BYTES), cipherName)
        val sub = Tlv8.decode(aead.open(labelNonce(MSG05), ByteArray(0), tlv[Tlv8.ENCRYPTED_DATA].orFail(6)))
        val controllerId = sub[Tlv8.IDENTIFIER].orFail(6)
        val controllerKey = sub[Tlv8.PUBLIC_KEY].orFail(6)
        val controllerX = Hkdf.sha512(key, CONTROLLER_SIGN_SALT, CONTROLLER_SIGN_INFO, KEY_BYTES)
        val signed = controllerX + controllerId + controllerKey
        if (!Ed25519KeyPair.verify(controllerKey, signed, sub[Tlv8.SIGNATURE].orFail(6))) throw PairingFailure(6)
        pairings.save(controllerId.decodeToString(), controllerKey)

        val accessoryId = identity.pairingId.encodeToByteArray()
        val accessoryX = Hkdf.sha512(key, ACCESSORY_SIGN_SALT, ACCESSORY_SIGN_INFO, KEY_BYTES)
        val signature = identity.keys.sign(accessoryX + accessoryId + identity.keys.publicKey)
        val sub6 =
            Tlv8.encode(
                Tlv8.IDENTIFIER to accessoryId,
                Tlv8.PUBLIC_KEY to identity.keys.publicKey,
                Tlv8.SIGNATURE to signature,
            )
        val sealed = aead.seal(labelNonce(MSG06), ByteArray(0), sub6)
        return Tlv8.encode(Tlv8.STATE to byteArrayOf(6), Tlv8.ENCRYPTED_DATA to sealed)
    }

    private companion object {
        /** CarPlay's fixed setup code (DiPlay PairSetup.kt:126); the iPhone never shows a code. */
        const val SETUP_CODE = "3939"
        const val KEY_BYTES = 32
        const val ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt"
        const val ENCRYPT_INFO = "Pair-Setup-Encrypt-Info"
        const val CONTROLLER_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt"
        const val CONTROLLER_SIGN_INFO = "Pair-Setup-Controller-Sign-Info"
        const val ACCESSORY_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt"
        const val ACCESSORY_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info"
        const val MSG05 = "PS-Msg05"
        const val MSG06 = "PS-Msg06"
    }
}

/** A pairing step failed; answered with `State = [state], Error = Authentication`. */
internal class PairingFailure(
    val state: Int,
) : Exception("pairing failed, reply state $state")

internal fun <T> T?.orFail(state: Int): T = this ?: throw PairingFailure(state)

/**
 * Decodes [body], runs [step] with the State TLV, and turns every failure into `State, Error = 2`
 * (DiPlay PairSetup.kt:108-113): a refused step answers with the state it would have sent, a bad seal or
 * malformed key with the next state (HAP's rule; DiPlay echoes the received one, spec C §10.2).
 */
internal inline fun handlePairing(
    body: ByteArray,
    step: (Int, Map<Int, ByteArray>) -> ByteArray,
): ByteArray {
    val tlv = Tlv8.decode(body)
    val state = tlv[Tlv8.STATE]?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
    return try {
        step(state, tlv)
    } catch (e: PairingFailure) {
        pairingError(e.state)
    } catch (_: GeneralSecurityException) {
        pairingError(state + 1)
    } catch (_: IllegalArgumentException) {
        pairingError(state + 1)
    }
}

/** `State, Error = 2 (Authentication)` (DiPlay PairSetup.kt:108-113). */
internal fun pairingError(state: Int): ByteArray =
    Tlv8.encode(Tlv8.STATE to byteArrayOf(state.toByte()), Tlv8.ERROR to byteArrayOf(2))

/** Four zero bytes, then an 8-byte ASCII label (DiPlay AirPlayCrypto.kt:229-236). */
internal fun labelNonce(label: String): ByteArray {
    val ascii = label.encodeToByteArray()
    require(ascii.size == 8) { "Nonce label must be 8 ASCII bytes" }
    return ByteArray(4) + ascii
}

/** Four zero bytes, then the counter as 8 little-endian bytes (DiPlay AirPlayCrypto.kt:219-227). */
internal fun counterNonce(counter: Long): ByteArray =
    ByteArray(12) { if (it < 4) 0 else (counter ushr (8 * (it - 4))).toByte() }

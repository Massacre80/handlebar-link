package com.abid.handlebarlink.carplay.protocol.pairing

import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.Srp6aServer
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import java.math.BigInteger
import java.security.SecureRandom

internal val JVM_CIPHER = ChaCha20Poly1305.Transformation.JVM
internal val SRP_USERNAME = "Pair-Setup".encodeToByteArray()

internal fun state(value: Int) = Tlv8.STATE to byteArrayOf(value.toByte())

internal fun aeadFrom(
    secret: ByteArray,
    salt: String,
    info: String,
) = ChaCha20Poly1305(Hkdf.sha512(secret, salt, info, 32), JVM_CIPHER)

internal fun ChaCha20Poly1305.sealLabel(
    label: String,
    tlv: ByteArray,
) = seal(labelNonce(label), ByteArray(0), tlv)

internal fun ChaCha20Poly1305.openLabel(
    label: String,
    sealed: ByteArray,
) = Tlv8.decode(open(labelNonce(label), ByteArray(0), sealed))

/** DiPlay's client proof M1 = H(H(N) xor H(g) | H(I) | s | PAD(A) | PAD(B) | K). */
internal fun srpClientProof(
    a: BigInteger,
    salt: ByteArray,
    serverKey: ByteArray,
    k: ByteArray,
): ByteArray = srpProof(Srp6aServer.pad(a), serverKey, salt, k)

/**
 * Apple's client proof: A and B without leading zero bytes, written from HomeKitADK HAPOpenSSL.c
 * HAP_srp_proof_m1 (`Count_Leading_Zeroes` on pub_a and pub_b), not from our server.
 */
internal fun appleClientProof(
    a: BigInteger,
    salt: ByteArray,
    serverKey: ByteArray,
    k: ByteArray,
): ByteArray =
    srpProof(
        Srp6aServer.pad(a).dropWhile { it == 0.toByte() }.toByteArray(),
        serverKey
            .dropWhile {
                it ==
                    0.toByte()
            }.toByteArray(),
        salt,
        k,
    )

private fun srpProof(
    a: ByteArray,
    b: ByteArray,
    salt: ByteArray,
    k: ByteArray,
): ByteArray {
    val hashN = Srp6aServer.sha512(Srp6aServer.unsigned(Srp6aServer.N))
    val hashG = Srp6aServer.sha512(byteArrayOf(5))
    val group = ByteArray(64) { (hashN[it].toInt() xor hashG[it].toInt()).toByte() }
    return Srp6aServer.sha512(group, Srp6aServer.sha512(SRP_USERNAME), salt, a, b, k)
}

/**
 * The iPhone's side of pairing for tests. SRP's A and S come from BouncyCastle's SRP6Client, an implementation
 * independent of ours; the proof follows Apple's formula (HomeKitADK); identities are Ed25519, sessions X25519.
 */
internal class SimulatedPhone(
    private val random: SecureRandom,
) {
    val id = "phone-pairing-id"
    val keys = Ed25519KeyPair.generate(random)
    private var ephemeral: X25519KeyPair? = null

    /** The X25519 secret of the last pair-verify. */
    var verifySecret: ByteArray = ByteArray(0)
        private set

    /** M3 of pair-setup for the M2 [m2] with setup [code]; returns the body and the session key K. */
    fun setupM3(
        m2: Map<Int, ByteArray>,
        code: String = "3939",
    ): Pair<ByteArray, ByteArray> {
        val client = SRP6Client().apply { init(SRP6StandardGroups.rfc5054_3072, SHA512Digest(), random) }
        val salt = m2.getValue(Tlv8.SALT)
        val a = client.generateClientCredentials(salt, SRP_USERNAME, code.encodeToByteArray())
        val s = client.calculateSecret(BigInteger(1, m2.getValue(Tlv8.PUBLIC_KEY)))
        val k = Srp6aServer.sha512(Srp6aServer.unsigned(s))
        val proof = appleClientProof(a, salt, m2.getValue(Tlv8.PUBLIC_KEY), k)
        return Tlv8.encode(state(3), Tlv8.PUBLIC_KEY to Srp6aServer.pad(a), Tlv8.PROOF to proof) to k
    }

    /** M5 of pair-setup under session key [k]. */
    fun setupM5(k: ByteArray): ByteArray {
        val x = Hkdf.sha512(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", 32)
        val signature = keys.sign(x + id.encodeToByteArray() + keys.publicKey)
        val inner =
            Tlv8.encode(
                Tlv8.IDENTIFIER to id.encodeToByteArray(),
                Tlv8.PUBLIC_KEY to keys.publicKey,
                Tlv8.SIGNATURE to signature,
            )
        return Tlv8.encode(state(5), Tlv8.ENCRYPTED_DATA to setupAead(k).sealLabel("PS-Msg05", inner))
    }

    fun setupAead(k: ByteArray) = aeadFrom(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")

    /** Runs pair-setup to the end; returns the session key and M6's decrypted content. */
    fun pairSetup(setup: PairSetup): Pair<ByteArray, Map<Int, ByteArray>> {
        val m2 = Tlv8.decode(setup.handle(Tlv8.encode(state(1))))
        val (m3, k) = setupM3(m2)
        check(Tlv8.decode(setup.handle(m3))[Tlv8.ERROR] == null) { "pair-setup M4 failed" }
        val m6 = Tlv8.decode(setup.handle(setupM5(k)))
        return k to setupAead(k).openLabel("PS-Msg06", m6.getValue(Tlv8.ENCRYPTED_DATA))
    }

    /** M1 of pair-verify with a fresh ephemeral key. */
    fun verifyM1(): ByteArray {
        val pair = X25519KeyPair.generate(random).also { ephemeral = it }
        return Tlv8.encode(state(1), Tlv8.PUBLIC_KEY to pair.publicKey)
    }

    /** M3 of pair-verify for [m2], signed by [signer]. */
    fun verifyM3(
        m2: Map<Int, ByteArray>,
        signer: Ed25519KeyPair = keys,
    ): ByteArray {
        val ours = checkNotNull(ephemeral)
        val accessoryKey = m2.getValue(Tlv8.PUBLIC_KEY)
        verifySecret = ours.agree(accessoryKey)
        val proof = signer.sign(ours.publicKey + id.encodeToByteArray() + accessoryKey)
        val inner = Tlv8.encode(Tlv8.IDENTIFIER to id.encodeToByteArray(), Tlv8.SIGNATURE to proof)
        return Tlv8.encode(state(3), Tlv8.ENCRYPTED_DATA to verifyAead().sealLabel("PV-Msg03", inner))
    }

    fun verifyAead() = aeadFrom(verifySecret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")

    val ephemeralPublicKey: ByteArray get() = checkNotNull(ephemeral).publicKey
}

package com.abid.handlebarlink.carplay.protocol.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** What the server sends in pair-setup M2: the salt and B, padded to 384 bytes. */
public class Srp6aChallenge(
    /** 16-byte salt. */
    public val salt: ByteArray,
    /** Server public value B, 384 bytes. */
    public val publicKey: ByteArray,
)

/** A verified exchange: the session key K and the server proof M2. */
public class Srp6aResult(
    /** K = H(S), 64 bytes. */
    public val sessionKey: ByteArray,
    /** M2 = H(PAD(A) | M1 | K), 64 bytes. */
    public val serverProof: ByteArray,
)

/**
 * SRP-6a verifier side for AirPlay pair-setup, exactly as DiPlay's Srp6a.kt (a port of LIVI's srp.ts;
 * spec C §2.3): RFC 5054 3072-bit group, g = 5, SHA-512, I = "Pair-Setup", password = the setup code.
 *
 *   k = H(N | PAD(g))         x = H(s | H(I | ":" | P))      v = g^x       B = k·v + g^b
 *   u = H(PAD(A) | PAD(B))    S = (A·v^u)^b                   K = H(S)  (S not padded)
 *   M1 = H((H(N) xor H(g)) | H(I) | s | A | B | K)          (H(g) of the single byte 05)
 *   M2 = H(PAD(A) | M1 | K)
 *
 * M1's A and B: Apple's own accessory code strips their leading zero bytes (HomeKitADK HAPOpenSSL.c
 * HAP_srp_proof_m1), DiPlay pads them to 384 bytes. The two differ only when A or B starts with a zero byte
 * (about 1 in 128 exchanges), so either form is accepted; both need K, so neither weakens the check.
 *
 * Unlike DiPlay it also refuses u = 0 and compares proofs in constant time (spec C §10.5).
 */
public class Srp6aServer internal constructor(
    password: String,
    private val salt: ByteArray,
    private val privateExponent: BigInteger,
) {
    /** A fresh exchange with random salt and b. */
    public constructor(password: String, random: SecureRandom) : this(
        password,
        ByteArray(SALT_BYTES).also(random::nextBytes),
        BigInteger(1, ByteArray(PRIVATE_BYTES).also(random::nextBytes)),
    )

    private val verifier: BigInteger
    private val publicKeyB: BigInteger

    init {
        val x = BigInteger(1, sha512(salt, sha512(USERNAME, ":".encodeToByteArray(), password.encodeToByteArray())))
        verifier = G.modPow(x, N)
        publicKeyB = (MULTIPLIER * verifier + G.modPow(privateExponent, N)).mod(N)
    }

    /** M2's values. */
    public val challenge: Srp6aChallenge get() = Srp6aChallenge(salt.copyOf(), pad(publicKeyB))

    /** Checks the client's [publicKeyA] and proof [clientProof]; null when they are wrong (bad code or tampering). */
    public fun verify(
        publicKeyA: ByteArray,
        clientProof: ByteArray,
    ): Srp6aResult? {
        val a = BigInteger(1, publicKeyA)
        val u = BigInteger(1, sha512(pad(a), pad(publicKeyB)))
        if (a.mod(N).signum() == 0 || u.signum() == 0) return null
        val s = (a * verifier.modPow(u, N)).mod(N).modPow(privateExponent, N)
        val sessionKey = sha512(unsigned(s))
        val hashN = sha512(unsigned(N))
        val hashG = sha512(unsigned(G))
        val groupHash = ByteArray(hashN.size) { (hashN[it].toInt() xor hashG[it].toInt()).toByte() }
        val hashUser = sha512(USERNAME)
        val apple = sha512(groupHash, hashUser, salt, unsigned(a), unsigned(publicKeyB), sessionKey)
        val padded = sha512(groupHash, hashUser, salt, pad(a), pad(publicKeyB), sessionKey)
        // Both compared in constant time, without short-circuiting.
        val matches = MessageDigest.isEqual(apple, clientProof) or MessageDigest.isEqual(padded, clientProof)
        return if (matches) {
            Srp6aResult(sessionKey, sha512(pad(a), clientProof, sessionKey))
        } else {
            null
        }
    }

    /** The group and helpers, shared with tests. */
    internal companion object {
        const val SALT_BYTES = 16
        const val PRIVATE_BYTES = 32
        const val N_BYTES = 384
        val USERNAME = "Pair-Setup".encodeToByteArray()

        /** RFC 5054 Appendix A, 3072-bit group. */
        val N =
            BigInteger(
                "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                    "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                    "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                    "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
                16,
            )
        val G: BigInteger = BigInteger.valueOf(5)
        val MULTIPLIER = BigInteger(1, sha512(unsigned(N), pad(G)))

        fun sha512(vararg parts: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").run {
                parts.forEach(::update)
                digest()
            }

        /** Minimal unsigned big-endian bytes. */
        fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        /** Left-padded to the group's 384 bytes. */
        fun pad(value: BigInteger): ByteArray {
            val bytes = unsigned(value)
            return if (bytes.size >= N_BYTES) bytes else ByteArray(N_BYTES - bytes.size) + bytes
        }
    }
}

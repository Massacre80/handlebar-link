package com.abid.handlebarlink.carplay.protocol.pairing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.Srp6aServer
import com.abid.handlebarlink.carplay.protocol.toHex
import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/** Vectors marked "spec C §9.2" were computed with Python's hashlib/hmac from DiPlay's formulas. */
class SrpTest {
    @Test
    fun `the group is RFC 5054's 3072-bit group, as BouncyCastle defines it`() {
        assertThat(Srp6aServer.N).isEqualTo(SRP6StandardGroups.rfc5054_3072.n)
        assertThat(Srp6aServer.G).isEqualTo(SRP6StandardGroups.rfc5054_3072.g)
    }

    @Test
    fun `k and H(N) xor H(g) match spec C 9_2`() {
        val n = Srp6aServer.unsigned(Srp6aServer.N)
        val hashN = Srp6aServer.sha512(n)
        val hashG = Srp6aServer.sha512(byteArrayOf(5))

        assertThat(Srp6aServer.sha512(n, Srp6aServer.pad(Srp6aServer.G)).toHex()).isEqualTo(
            "a9c2e2559bf0ebb53f0cbbf62282906bede7f2182f00678211fbd5bde5b285033a4993503b87397f9be5ec02080fedbc" +
                "0835587ad039060879b8621e8c3659e0",
        )
        assertThat(ByteArray(64) { (hashN[it].toInt() xor hashG[it].toInt()).toByte() }.toHex()).isEqualTo(
            "b3d63ef6aafb2e9796dae006fe60f20e5326fe1e1c52a5021eb21747ca33dfea8ff203bd45a2544395d73d19899c174a" +
                "683c9e783296dd1693ebc71cf5a53da3",
        )
    }

    @Test
    fun `derived keys and nonces match spec C 9_2`() {
        val ikm32 = ByteArray(32) { it.toByte() }
        val ikm64 = ByteArray(64) { it.toByte() }

        assertThat(Hkdf.sha512(ikm32, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", 32).toHex())
            .isEqualTo("faf9f3558a8ed1e45219bd94fb6d27e5b43a1bc861157fc2a0d291d8e3df410a")
        assertThat(Hkdf.sha512(ikm32, "Control-Salt", "Control-Write-Encryption-Key", 32).toHex())
            .isEqualTo("c3ca130c7033dbe5e7ff7f91d117ead869bac476994c7a48ca170c111136ed96")
        assertThat(Hkdf.sha512(ikm64, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", 32).toHex())
            .isEqualTo("b6335536162d6629e4c0bade85f1b712c85a364bab0dedb25014cfb814489273")
        assertThat(labelNonce("PV-Msg02").toHex()).isEqualTo("0000000050562d4d73673032")
        assertThat(counterNonce(256).toHex()).isEqualTo("000000000001000000000000")
    }

    @Test
    fun `the session key hashes S unpadded even when S has a leading zero byte`() {
        // About 1 in 256 exchanges has a short S; find one deterministically.
        val seeded = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(SEED) }
        var found = false
        while (!found) {
            val salt = ByteArray(16).also(seeded::nextBytes)
            val server = Srp6aServer("3939", salt, BigInteger(1, ByteArray(32).also(seeded::nextBytes)))
            val client = SRP6Client().apply { init(SRP6StandardGroups.rfc5054_3072, SHA512Digest(), seeded) }
            val a = client.generateClientCredentials(salt, SRP_USERNAME, "3939".encodeToByteArray())
            val serverKey = server.challenge.publicKey
            val s = client.calculateSecret(BigInteger(1, serverKey))
            if (Srp6aServer.unsigned(s).size < 384) {
                val k = Srp6aServer.sha512(Srp6aServer.unsigned(s))
                val result = server.verify(Srp6aServer.pad(a), srpClientProof(a, salt, serverKey, k))
                assertThat(result?.sessionKey?.toHex()).isEqualTo(k.toHex())
                found = true
            }
        }
        assertThat(found).isTrue()
    }

    @Test
    fun `Apple's proof with A and B stripped of leading zeros is accepted, as is DiPlay's padded one`() {
        // Find, deterministically, an exchange where A starts with a zero byte, and one where B does.
        val seeded = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(SEED) }
        var shortA = false
        var shortB = false
        while (!shortA || !shortB) {
            val salt = ByteArray(16).also(seeded::nextBytes)
            val server = Srp6aServer("3939", salt, BigInteger(1, ByteArray(32).also(seeded::nextBytes)))
            val serverKey = server.challenge.publicKey
            val bIsShort = serverKey[0] == 0.toByte()
            val client = SRP6Client().apply { init(SRP6StandardGroups.rfc5054_3072, SHA512Digest(), seeded) }
            val a = client.generateClientCredentials(salt, SRP_USERNAME, "3939".encodeToByteArray())
            val aIsShort = Srp6aServer.pad(a)[0] == 0.toByte()
            val newCase = (aIsShort && !shortA) || (bIsShort && !shortB)
            if (newCase) {
                val k = Srp6aServer.sha512(Srp6aServer.unsigned(client.calculateSecret(BigInteger(1, serverKey))))
                val apple = server.verify(Srp6aServer.pad(a), appleClientProof(a, salt, serverKey, k))
                val padded = server.verify(Srp6aServer.pad(a), srpClientProof(a, salt, serverKey, k))
                assertThat(apple?.sessionKey?.toHex()).isEqualTo(k.toHex())
                assertThat(padded?.sessionKey?.toHex()).isEqualTo(k.toHex())
                shortA = shortA || aIsShort
                shortB = shortB || bIsShort
            }
        }
    }

    private companion object {
        const val SEED = 20261118L
    }
}

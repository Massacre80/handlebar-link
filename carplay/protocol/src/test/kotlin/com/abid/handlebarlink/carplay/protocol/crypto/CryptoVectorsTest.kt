package com.abid.handlebarlink.carplay.protocol.crypto

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.hex
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/** Test vectors copied from the RFCs (rfc-editor.org text, fetched 2026-11-18); never computed here. */
class CryptoVectorsTest {
    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val okm =
            Hkdf.derive(
                Hkdf.SHA256,
                ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                salt = hex("000102030405060708090a0b0c"),
                info = hex("f0f1f2f3f4f5f6f7f8f9"),
                length = 42,
            )

        assertThat(okm.toHex()).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        )
    }

    @Test
    fun `HKDF SHA-512 gives the requested length and depends on salt and info`() {
        val a = Hkdf.sha512(ByteArray(32) { 1 }, "Control-Salt", "Control-Read-Encryption-Key", 32)
        val b = Hkdf.sha512(ByteArray(32) { 1 }, "Control-Salt", "Control-Write-Encryption-Key", 32)

        assertThat(a.size).isEqualTo(32)
        assertThat(a.contentEquals(b)).isFalse()
    }

    @Test
    fun `HKDF rejects lengths outside RFC 5869 limits`() {
        assertFailure { Hkdf.sha512(ByteArray(1), "s", "i", 0) }.isInstanceOf<IllegalArgumentException>()
        assertFailure { Hkdf.sha512(ByteArray(1), "s", "i", 255 * 64 + 1) }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `ChaCha20-Poly1305 matches RFC 8439 section 2_8_2`() {
        val aead = ChaCha20Poly1305(RFC8439_KEY, ChaCha20Poly1305.Transformation.JVM)

        val sealed = aead.seal(RFC8439_NONCE, RFC8439_AAD, RFC8439_PLAINTEXT)

        assertThat(sealed.toHex()).isEqualTo(RFC8439_CIPHERTEXT.toHex() + RFC8439_TAG.toHex())
        assertThat(aead.open(RFC8439_NONCE, RFC8439_AAD, sealed).contentEquals(RFC8439_PLAINTEXT)).isTrue()
    }

    @Test
    fun `ChaCha20-Poly1305 opens a slice in place of a copy`() {
        val aead = ChaCha20Poly1305(RFC8439_KEY, ChaCha20Poly1305.Transformation.JVM)
        val framed = byteArrayOf(9, 9) + RFC8439_CIPHERTEXT + RFC8439_TAG + byteArrayOf(9)

        val plain = aead.open(RFC8439_NONCE, RFC8439_AAD, framed, 2, framed.size - 3)

        assertThat(plain.contentEquals(RFC8439_PLAINTEXT)).isTrue()
    }

    @Test
    fun `ChaCha20-Poly1305 refuses a changed byte`() {
        val aead = ChaCha20Poly1305(RFC8439_KEY, ChaCha20Poly1305.Transformation.JVM)
        val tampered = RFC8439_CIPHERTEXT + RFC8439_TAG
        tampered[0] = (tampered[0].toInt() xor 1).toByte()

        assertFailure { aead.open(RFC8439_NONCE, RFC8439_AAD, tampered) }.isInstanceOf<AEADBadTagException>()
    }

    @Test
    fun `X25519 matches RFC 7748 section 6_1`() {
        val alice = X25519KeyPair.fromPrivate(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val bob = X25519KeyPair.fromPrivate(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))

        assertThat(
            alice.publicKey.toHex(),
        ).isEqualTo("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        assertThat(bob.publicKey.toHex()).isEqualTo("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertThat(alice.agree(bob.publicKey).toHex())
            .isEqualTo("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
    }

    @Test
    fun `X25519 rejects a small-order peer key`() {
        val pair = X25519KeyPair.generate(SecureRandom())

        assertFailure { pair.agree(ByteArray(32)) }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `Ed25519 matches RFC 8032 test 1`() {
        val pair = Ed25519KeyPair.fromSeed(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))

        val signature = pair.sign(ByteArray(0))

        assertThat(pair.publicKey.toHex()).isEqualTo("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        assertThat(signature.toHex()).isEqualTo(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b" +
                "d25bf5f0595bbe24655141438e7a100b",
        )
        assertThat(Ed25519KeyPair.verify(pair.publicKey, ByteArray(0), signature)).isTrue()
    }

    @Test
    fun `Ed25519 verification fails for another message or a malformed key`() {
        val pair = Ed25519KeyPair.generate(SecureRandom())
        val signature = pair.sign(byteArrayOf(1))

        assertThat(Ed25519KeyPair.verify(pair.publicKey, byteArrayOf(2), signature)).isFalse()
        assertThat(Ed25519KeyPair.verify(ByteArray(3), byteArrayOf(1), signature)).isFalse()
        assertThat(Ed25519KeyPair.fromSeed(pair.seed).publicKey.contentEquals(pair.publicKey)).isTrue()
    }

    private companion object {
        val RFC8439_KEY = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")

        // 32-bit fixed-common part 07 00 00 00, then the IV 40..47 (RFC 8439 §2.8.2).
        val RFC8439_NONCE = hex("07000000 4041424344454647")
        val RFC8439_AAD = hex("50515253c0c1c2c3c4c5c6c7")
        val RFC8439_PLAINTEXT =
            (
                "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
                    "sunscreen would be it."
            ).encodeToByteArray()
        val RFC8439_CIPHERTEXT =
            hex(
                "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
                    "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                    "3ff4def08e4b7a9de576d26586cec64b6116",
            )
        val RFC8439_TAG = hex("1ae10b594f09e26a7e902ecbd0600691")
    }
}

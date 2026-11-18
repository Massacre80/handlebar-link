package com.abid.handlebarlink.carplay.protocol.mfi

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.toHex
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory

/** Uses the throwaway openssl identity in src/test/resources/mfi (see its README). */
class MfiIdentityTest {
    private val random = SecureRandom()

    @Test
    fun `a PKCS#7 certificate is accepted and sent byte for byte`() {
        val identity = MfiIdentity.load(resource("test-identity.pk8"), resource("test-certificate.p7b"), random)

        assertThat(identity.certificate.toHex()).isEqualTo(resource("test-certificate.p7b").toHex())
    }

    @Test
    fun `a bare DER certificate is accepted too`() {
        MfiIdentity.load(resource("test-identity.pk8"), resource("test-certificate.der"), random)
    }

    @Test
    fun `the challenge is signed as a digest, without hashing it again, as 64 raw bytes`() {
        val identity = MfiIdentity.load(resource("test-identity.pk8"), resource("test-certificate.p7b"), random)
        val digest = ByteArray(32) { it.toByte() }

        val raw = identity.sign(digest)

        assertThat(raw.size).isEqualTo(64)
        val der =
            DERSequence(
                arrayOf(
                    ASN1Integer(BigInteger(1, raw.copyOfRange(0, 32))),
                    ASN1Integer(BigInteger(1, raw.copyOfRange(32, 64))),
                ),
            ).encoded
        val certificate =
            CertificateFactory
                .getInstance(
                    "X.509",
                ).generateCertificate(resource("test-certificate.der").inputStream())
        val verifier = Signature.getInstance("NONEwithECDSA").apply { initVerify(certificate.publicKey) }
        verifier.update(digest)
        assertThat(verifier.verify(der)).isTrue()
    }

    @Test
    fun `a challenge that is not 32 bytes is refused`() {
        val identity = MfiIdentity.load(resource("test-identity.pk8"), resource("test-certificate.p7b"), random)

        assertFailure { identity.sign(ByteArray(20)) }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `a key that does not match the certificate is refused`() {
        assertFailure { MfiIdentity.load(resource("other-identity.pk8"), resource("test-certificate.p7b"), random) }
            .isInstanceOf<MfiIdentityException>()
    }

    @Test
    fun `unreadable or empty files are refused`() {
        assertFailure { MfiIdentity.load(byteArrayOf(1, 2), resource("test-certificate.p7b"), random) }
            .isInstanceOf<MfiIdentityException>()
        assertFailure { MfiIdentity.load(resource("test-identity.pk8"), byteArrayOf(1, 2), random) }
            .isInstanceOf<MfiIdentityException>()
        assertFailure { MfiIdentity.load(ByteArray(0), resource("test-certificate.p7b"), random) }
            .isInstanceOf<MfiIdentityException>()
    }

    @Test
    fun `DER integers are left-padded or stripped to 32 bytes each`() {
        val small = BigInteger.ONE
        val high = BigInteger("ff" + "00".repeat(31), 16) // 33 bytes in DER, with a leading zero
        val der = DERSequence(arrayOf(ASN1Integer(small), ASN1Integer(high))).encoded

        val raw = MfiIdentity.derToRaw(der)

        assertThat(raw.toHex()).isEqualTo("00".repeat(31) + "01" + "ff" + "00".repeat(31))
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(
            javaClass.getResourceAsStream("/mfi/$name"),
        ) { "missing test resource $name" }.use { it.readBytes() }
}

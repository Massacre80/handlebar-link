package com.abid.handlebarlink.carplay.protocol.pairing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.carplay.protocol.toHex
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Plays the iPhone side of MFi-SAP against DiPlay's layout (spec C §6). */
class AuthSetupTest {
    private val random = SecureRandom()
    private val mfi = MfiIdentity.load(resource("test-identity.pk8"), resource("test-certificate.p7b"), random)

    @Test
    fun `the reply carries our key, the certificate and an encrypted signature the iPhone can verify`() {
        val phone = X25519KeyPair.generate(random)

        val reply = ByteBuffer.wrap(AuthSetup.handle(byteArrayOf(1) + phone.publicKey, mfi, random)!!)

        val ourKey = ByteArray(32).also(reply::get)
        val certificate = ByteArray(reply.int).also(reply::get)
        val encrypted = ByteArray(reply.int).also(reply::get)
        assertThat(reply.remaining()).isEqualTo(0)
        assertThat(certificate.toHex()).isEqualTo(resource("test-certificate.p7b").toHex())
        val shared = phone.agree(ourKey)
        val key = sha("SHA-1", "AES-KEY".encodeToByteArray() + shared).copyOf(16)
        val iv = sha("SHA-1", "AES-IV".encodeToByteArray() + shared).copyOf(16)
        val raw =
            Cipher.getInstance("AES/CTR/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(encrypted)
            }
        val r = ASN1Integer(BigInteger(1, raw.copyOf(32)))
        val s = ASN1Integer(BigInteger(1, raw.copyOfRange(32, 64)))
        val der = DERSequence(arrayOf(r, s)).encoded
        val derCertificate = resource("test-certificate.der").inputStream()
        val publicKey = CertificateFactory.getInstance("X.509").generateCertificate(derCertificate).publicKey
        val verifier = Signature.getInstance("NONEwithECDSA").apply { initVerify(publicKey) }
        verifier.update(sha("SHA-256", ourKey + phone.publicKey))
        assertThat(verifier.verify(der)).isTrue()
    }

    @Test
    fun `a request with the wrong version, length or a small-order key is refused`() {
        assertThat(AuthSetup.handle(byteArrayOf(2) + ByteArray(32) { 9 }, mfi, random)).isNull()
        assertThat(AuthSetup.handle(byteArrayOf(1) + ByteArray(31), mfi, random)).isNull()
        assertThat(AuthSetup.handle(byteArrayOf(1) + ByteArray(32), mfi, random)).isNull()
    }

    private fun sha(
        algorithm: String,
        data: ByteArray,
    ) = MessageDigest.getInstance(algorithm).digest(data)

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mfi/$name")).use { it.readBytes() }
}

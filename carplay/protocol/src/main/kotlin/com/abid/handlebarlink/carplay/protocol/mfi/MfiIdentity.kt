package com.abid.handlebarlink.carplay.protocol.mfi

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The accessory's MFi identity from the owner's own CarPlay display: `identity.pk8` (PKCS#8 P-256 key)
 * and `certificate.p7b`, kept outside git in `~/.carlink-private/offline-mfi` and injected at build time.
 * Behaves like DiPlay's LocalMfiAuthenticationClient.kt:20-105 (spec B §4.1):
 * - the certificate file goes to the iPhone byte for byte (PKCS#7 bundle or bare DER, whichever the file is);
 * - the iPhone's 32-byte challenge is already a digest and is signed as is (NONEwithECDSA, no second hash);
 * - the signature is raw r‖s, 64 bytes.
 *
 * Signing runs on the platform provider (Conscrypt on Android, the JDK's on the JVM).
 */
public class MfiIdentity private constructor(
    private val privateKey: PrivateKey,
    private val certificateFile: ByteArray,
) {
    /** The certificate bytes sent in AuthenticationCertificate and in AirPlay auth-setup. */
    public val certificate: ByteArray get() = certificateFile.copyOf()

    /** Signs the 32-byte [digest]; returns raw r‖s (64 bytes). */
    public fun sign(digest: ByteArray): ByteArray {
        require(digest.size == DIGEST_BYTES) { "MFi v3 signs a $DIGEST_BYTES-byte digest, got ${digest.size}" }
        val signer = Signature.getInstance(ALGORITHM).apply { initSign(privateKey) }
        signer.update(digest)
        return derToRaw(signer.sign())
    }

    /** Loading and checking an identity. */
    public companion object {
        private const val ALGORITHM = "NONEwithECDSA"
        private const val DIGEST_BYTES = 32
        private const val COMPONENT_BYTES = 32
        private const val MAX_FILE_BYTES = 16 * 1024

        /** The order of the P-256 group (DiPlay checks the same, LocalMfiAuthenticationClient.kt:60-65). */
        private val P256_ORDER = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

        /**
         * Parses and checks the two files: one P-256 certificate, and a key that signs what the certificate
         * verifies (DiPlay LocalMfiAuthenticationClient.kt:48-75).
         *
         * @throws MfiIdentityException when the files are missing parts or do not belong together.
         */
        public fun load(
            pkcs8Key: ByteArray,
            certificateFile: ByteArray,
            random: SecureRandom,
        ): MfiIdentity {
            check(pkcs8Key.size in 1..MAX_FILE_BYTES && certificateFile.size in 1..MAX_FILE_BYTES, "MFi file size")
            val privateKey =
                try {
                    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8Key))
                } catch (e: java.security.GeneralSecurityException) {
                    throw MfiIdentityException("identity.pk8 is not a PKCS#8 EC key", e)
                }
            val certificates =
                try {
                    CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(certificateFile))
                } catch (e: java.security.cert.CertificateException) {
                    throw MfiIdentityException("certificate.p7b holds no readable certificate", e)
                }
            check(certificates.size == 1, "exactly one certificate")
            val publicKey = certificates.single().publicKey as? ECPublicKey
            check(publicKey?.params?.order == P256_ORDER, "a P-256 certificate")
            val probe = ByteArray(DIGEST_BYTES).also(random::nextBytes)
            val signature =
                Signature.getInstance(ALGORITHM).apply { initSign(privateKey) }.run {
                    update(probe)
                    sign()
                }
            val verifier = Signature.getInstance(ALGORITHM).apply { initVerify(publicKey) }
            verifier.update(probe)
            check(verifier.verify(signature), "the key to match the certificate")
            return MfiIdentity(privateKey, certificateFile.copyOf())
        }

        private fun check(
            condition: Boolean,
            expected: String,
        ) {
            if (!condition) throw MfiIdentityException("MFi identity check failed: expected $expected")
        }

        /** DER ECDSA signature to raw r‖s, each left-padded to 32 bytes (LocalMfiAuthenticationClient.kt:90-103). */
        internal fun derToRaw(der: ByteArray): ByteArray {
            val sequence = ASN1Sequence.getInstance(der)
            require(sequence.size() == 2) { "Invalid ECDSA signature" }
            val raw = ByteArray(2 * COMPONENT_BYTES)
            for (index in 0..1) {
                val value = ASN1Integer.getInstance(sequence.getObjectAt(index)).value
                require(value.signum() > 0 && value.bitLength() <= 8 * COMPONENT_BYTES) { "Invalid ECDSA integer" }
                val bytes = value.toByteArray()
                val skip = if (bytes.size > COMPONENT_BYTES) bytes.size - COMPONENT_BYTES else 0
                bytes.copyInto(raw, index * COMPONENT_BYTES + COMPONENT_BYTES - (bytes.size - skip), skip)
            }
            return raw
        }
    }
}

/** The MFi files are unusable. */
public class MfiIdentityException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

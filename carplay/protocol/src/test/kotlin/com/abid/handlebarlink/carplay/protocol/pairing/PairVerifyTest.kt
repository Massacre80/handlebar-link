package com.abid.handlebarlink.carplay.protocol.pairing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.X25519KeyPair
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test
import java.security.SecureRandom

/** Pair-verify against a simulated iPhone (DiPlay PairVerify.kt with PR #1124, spec C §4). */
class PairVerifyTest {
    private val random = SecureRandom()
    private val identity = AirPlayIdentity.generate(random)
    private val pairings = InMemoryPairingStore()
    private val phone = SimulatedPhone(random)
    private val verify = PairVerify(identity, pairings, random, JVM_CIPHER)

    @Test
    fun `a paired iPhone completes pair-verify and both sides derive the same control keys`() {
        phone.pairSetup(PairSetup(identity, pairings, random, JVM_CIPHER))

        val m2 = Tlv8.decode(verify.handle(phone.verifyM1()))
        val m3 = phone.verifyM3(m2)
        val inner = phone.verifyAead().openLabel("PV-Msg02", m2.getValue(Tlv8.ENCRYPTED_DATA))
        val signed = m2.getValue(Tlv8.PUBLIC_KEY) + inner.getValue(Tlv8.IDENTIFIER) + phone.ephemeralPublicKey
        assertThat(Ed25519KeyPair.verify(identity.keys.publicKey, signed, inner.getValue(Tlv8.SIGNATURE))).isTrue()
        assertThat(verify.controlKeys).isNull()
        val m4 = Tlv8.decode(verify.handle(m3))

        assertThat(m4.getValue(Tlv8.STATE).single().toInt()).isEqualTo(4)
        assertThat(m4[Tlv8.ERROR]).isNull()
        assertThat(verify.controllerId).isEqualTo(phone.id)
        val keys = verify.controlKeys!!
        val phoneWrite = Hkdf.sha512(phone.verifySecret, "Control-Salt", "Control-Write-Encryption-Key", 32)
        assertThat(keys.readKey.toHex()).isEqualTo(phoneWrite.toHex())
        val phoneSide = ControlCipher(readKey = keys.writeKey, writeKey = keys.readKey, cipherName = JVM_CIPHER)
        val ourSide = ControlCipher(keys.readKey, keys.writeKey, JVM_CIPHER)
        val request = "GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n"
        val wire = phoneSide.encrypt(request.encodeToByteArray())
        assertThat(ourSide.decrypt(wire, 0, wire.size).decodeToString()).isEqualTo(request)
    }

    @Test
    fun `a new M1 on a verified connection keeps the verified secret until a new M4 succeeds`() {
        phone.pairSetup(PairSetup(identity, pairings, random, JVM_CIPHER))
        verify.handle(phone.verifyM3(Tlv8.decode(verify.handle(phone.verifyM1()))))
        val secret = verify.sharedSecret!!.toHex()

        verify.handle(phone.verifyM1())

        // DiPlay PairVerify.kt:51: later channels derive their keys from this secret.
        assertThat(verify.sharedSecret?.toHex()).isEqualTo(secret)
        assertThat(verify.controllerId).isEqualTo(phone.id)
    }

    @Test
    fun `an iPhone that never paired is refused and no secret is published`() {
        val m2 = Tlv8.decode(verify.handle(phone.verifyM1()))

        val m4 = Tlv8.decode(verify.handle(phone.verifyM3(m2)))

        assertThat(m4.getValue(Tlv8.ERROR).single().toInt()).isEqualTo(2)
        assertThat(verify.sharedSecret).isNull()
        assertThat(verify.controlKeys).isNull()
    }

    @Test
    fun `a paired iPhone whose M3 signature does not verify is refused`() {
        phone.pairSetup(PairSetup(identity, pairings, random, JVM_CIPHER))
        val m2 = Tlv8.decode(verify.handle(phone.verifyM1()))

        val m4 = Tlv8.decode(verify.handle(phone.verifyM3(m2, signer = Ed25519KeyPair.generate(random))))

        assertThat(m4.getValue(Tlv8.ERROR).single().toInt()).isEqualTo(2)
        assertThat(verify.controlKeys).isNull()
    }

    @Test
    fun `a small-order key and a tampered seal are refused`() {
        val zero = Tlv8.decode(verify.handle(Tlv8.encode(state(1), Tlv8.PUBLIC_KEY to ByteArray(32))))
        verify.handle(Tlv8.encode(state(1), Tlv8.PUBLIC_KEY to X25519KeyPair.generate(random).publicKey))
        val tampered = Tlv8.decode(verify.handle(Tlv8.encode(state(3), Tlv8.ENCRYPTED_DATA to ByteArray(40))))

        assertThat(zero.getValue(Tlv8.ERROR).single().toInt()).isEqualTo(2)
        assertThat(tampered.getValue(Tlv8.ERROR).single().toInt()).isEqualTo(2)
        assertThat(verify.controlKeys).isNull()
    }
}

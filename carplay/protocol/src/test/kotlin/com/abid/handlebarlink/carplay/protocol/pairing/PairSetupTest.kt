package com.abid.handlebarlink.carplay.protocol.pairing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.crypto.Srp6aServer
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test
import java.security.SecureRandom

/** Pair-setup against a simulated iPhone (DiPlay PairSetup.kt, spec C §2). */
class PairSetupTest {
    private val random = SecureRandom()
    private val identity = AirPlayIdentity.generate(random)
    private val pairings = InMemoryPairingStore()
    private val phone = SimulatedPhone(random)
    private val setup = PairSetup(identity, pairings, random, JVM_CIPHER)

    @Test
    fun `an iPhone completes pair-setup, is remembered, and can verify our signed identity`() {
        val (k, m6) = phone.pairSetup(setup)

        assertThat(pairings.get(phone.id)!!.toHex()).isEqualTo(phone.keys.publicKey.toHex())
        assertThat(m6.getValue(Tlv8.IDENTIFIER).decodeToString()).isEqualTo(identity.pairingId)
        assertThat(m6.getValue(Tlv8.PUBLIC_KEY).toHex()).isEqualTo(identity.keys.publicKey.toHex())
        val x = Hkdf.sha512(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", 32)
        val signed = x + m6.getValue(Tlv8.IDENTIFIER) + m6.getValue(Tlv8.PUBLIC_KEY)
        assertThat(Ed25519KeyPair.verify(identity.keys.publicKey, signed, m6.getValue(Tlv8.SIGNATURE))).isTrue()
    }

    @Test
    fun `M4 carries the server proof, the hash of padded A, the client proof and K`() {
        val m2 = Tlv8.decode(setup.handle(Tlv8.encode(state(1))))
        val (m3, k) = phone.setupM3(m2)
        val sent = Tlv8.decode(m3)

        val m4 = Tlv8.decode(setup.handle(m3))

        val expected = Srp6aServer.sha512(sent.getValue(Tlv8.PUBLIC_KEY), sent.getValue(Tlv8.PROOF), k)
        assertThat(m4.getValue(Tlv8.PROOF).toHex()).isEqualTo(expected.toHex())
    }

    @Test
    fun `M2 lists state, public key, then salt, as DiPlay`() {
        val m2 = setup.handle(Tlv8.encode(state(1)))

        // 06 01 02 | 03 FF <255 bytes> | 03 81 <129 bytes> | 02 10 <16 bytes>  (DiPlay PairSetup.kt:34-44)
        assertThat(m2.copyOfRange(0, 5).toHex()).isEqualTo("06010203ff")
        assertThat(m2[3 + 2 + 255].toInt()).isEqualTo(Tlv8.PUBLIC_KEY)
        assertThat(m2[3 + 2 + 255 + 2 + 129].toInt()).isEqualTo(Tlv8.SALT)
    }

    @Test
    fun `a wrong setup code fails at M4 with an authentication error`() {
        val m2 = Tlv8.decode(setup.handle(Tlv8.encode(state(1))))

        val m4 = Tlv8.decode(setup.handle(phone.setupM3(m2, code = "1234").first))

        assertThat(m4.getValue(Tlv8.STATE).single().toInt()).isEqualTo(4)
        assertThat(m4.getValue(Tlv8.ERROR).single().toInt()).isEqualTo(2)
    }

    @Test
    fun `out-of-order or unknown states are answered with an error`() {
        val early = Tlv8.decode(setup.handle(Tlv8.encode(state(5), Tlv8.ENCRYPTED_DATA to ByteArray(20))))
        val unknown = Tlv8.decode(setup.handle(Tlv8.encode(state(9))))

        assertThat(early.getValue(Tlv8.ERROR).single().toInt()).isEqualTo(2)
        assertThat(unknown.getValue(Tlv8.STATE).single().toInt()).isEqualTo(9)
    }

    @Test
    fun `a tampered M5 is refused and nothing is stored`() {
        val m2 = Tlv8.decode(setup.handle(Tlv8.encode(state(1))))
        val (m3, k) = phone.setupM3(m2)
        setup.handle(m3)
        val m5 = Tlv8.decode(phone.setupM5(k)).getValue(Tlv8.ENCRYPTED_DATA).also { it[0] = (it[0] + 1).toByte() }

        val m6 = Tlv8.decode(setup.handle(Tlv8.encode(state(5), Tlv8.ENCRYPTED_DATA to m5)))

        assertThat(m6.getValue(Tlv8.ERROR).single().toInt()).isEqualTo(2)
        assertThat(pairings.get(phone.id)).isEqualTo(null)
    }
}

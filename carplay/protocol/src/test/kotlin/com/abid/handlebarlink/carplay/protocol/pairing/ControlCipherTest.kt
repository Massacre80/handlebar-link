package com.abid.handlebarlink.carplay.protocol.pairing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.crypto.Hkdf
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID

/** ControlCipher framing (DiPlay ControlCipher.kt, spec C §5), TLV8 (Tlv8Codec.kt) and identities. */
class ControlCipherTest {
    @Test
    fun `the control cipher splits at 16 KiB and reassembles frames that arrive byte by byte`() {
        val a = ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }, JVM_CIPHER)
        val b = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 }, JVM_CIPHER)
        val plaintext = ByteArray(ControlCipher.MAX_PAYLOAD + 10) { it.toByte() }

        val wire = a.encrypt(plaintext)
        val received = ByteArrayOutputStream()
        wire.indices.forEach { received.write(b.decrypt(wire, it, 1)) }

        assertThat(wire.size).isEqualTo(plaintext.size + 2 * (2 + 16))
        val firstLength = (wire[0].toInt() and 0xFF) or ((wire[1].toInt() and 0xFF) shl 8)
        assertThat(firstLength).isEqualTo(ControlCipher.MAX_PAYLOAD)
        assertThat(received.toByteArray().contentEquals(plaintext)).isTrue()
    }

    @Test
    fun `event channel keys are named the other way round from the control channel`() {
        val shared = ByteArray(32) { it.toByte() }
        val ours = ControlCipher.events(shared, JVM_CIPHER)
        val phoneWrite = Hkdf.sha512(shared, "Events-Salt", "Events-Read-Encryption-Key", 32)
        val phoneRead = Hkdf.sha512(shared, "Events-Salt", "Events-Write-Encryption-Key", 32)

        val wire = ControlCipher(phoneRead, phoneWrite, JVM_CIPHER).encrypt(byteArrayOf(42))

        assertThat(ours.decrypt(wire, 0, wire.size).single().toInt()).isEqualTo(42)
        // Spec C §9.2 (computed): Events-Salt / Events-Write-Encryption-Key of 00..1f.
        assertThat(phoneRead.toHex()).isEqualTo("24ee7bb1f775cc08cc23e25bee48afa439250299bc76549e80c0b54d9cc60b61")
    }

    @Test
    fun `TLV8 fragments long values, separates repeats, and joins fragments back`() {
        val long = ByteArray(600) { it.toByte() }

        val encoded = Tlv8.encode(Tlv8.PUBLIC_KEY to long, state(1), state(2))

        assertThat(encoded.size).isEqualTo(600 + 3 * 2 + 3 + 2 + 3)
        assertThat(Tlv8.decode(encoded).getValue(Tlv8.PUBLIC_KEY).contentEquals(long)).isTrue()
        assertThat(
            Tlv8
                .decode(encoded)
                .getValue(Tlv8.STATE)
                .single()
                .toInt(),
        ).isEqualTo(2)
        assertThat(Tlv8.decode(byteArrayOf(4, 0)).getValue(4).size).isEqualTo(0)
        assertThat(Tlv8.decode(byteArrayOf(1, 5, 1)).containsKey(1)).isFalse()
    }

    @Test
    fun `identities have a version 4 UUID and a 64-character key`() {
        val generated = AirPlayIdentity.generate(SecureRandom())

        assertThat(generated.pairingId[14]).isEqualTo('4')
        assertThat(generated.publicKeyHex.length).isEqualTo(64)
        assertThat(UUID.fromString(generated.pairingId)).isNotNull()
    }
}

package com.abid.handlebarlink.carplay.protocol.audio

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.abid.handlebarlink.carplay.protocol.bouncyCastleSeal
import com.abid.handlebarlink.carplay.protocol.hex
import com.abid.handlebarlink.carplay.protocol.pairing.JVM_CIPHER
import com.abid.handlebarlink.carplay.protocol.stream.MediaStreamException
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test

/**
 * Audio packets against packets sealed by Python's `cryptography` from DiPlay's layout
 * (src/test/resources/streams/generate_vectors.py; AudioStream.kt:131-139, MicrophonePacketizer.kt:95-126,
 * BufferedAudioStream.kt:89-111, 358-370) and DiPlay's test literals.
 */
class AudioPacketsTest {
    private val key = ByteArray(32) { (it + 1).toByte() } // MicrophonePacketizerTest.kt:11

    @Test
    fun `a realtime packet opens to its RTP header and payload`() {
        val packet = RealtimeAudioDecryptor(key, JVM_CIPHER).open(AUDIO)

        assertThat(packet).isNotNull()
        packet!!
        assertThat(packet.sequence).isEqualTo(7)
        assertThat(packet.timestamp).isEqualTo(0xFFFFFC00L)
        assertThat(packet.payloadType).isEqualTo(0x60)
        assertThat(packet.payload().toHex()).isEqualTo("216c4f5510")
        assertThat(packet.rtp.toHex()).isEqualTo("80600007fffffc0011223344" + "216c4f5510")
        // Handed on once without a copy; payload() still copies.
        assertThat(packet.takePayload().toHex()).isEqualTo("216c4f5510")
        assertThat(packet.takePayload()).isSameInstanceAs(packet.takePayload())
        assertThat(packet.payload()).isNotSameInstanceAs(packet.takePayload())
    }

    @Test
    fun `a packet inside a larger receive buffer opens from its slice`() {
        val buffer = ByteArray(5) + AUDIO + ByteArray(9)

        val packet = RealtimeAudioDecryptor(key, JVM_CIPHER).open(buffer, 5, AUDIO.size)

        assertThat(packet?.payload()?.toHex()).isEqualTo("216c4f5510")
    }

    @Test
    fun `only RTP bytes 4 to 11 are associated data`() {
        val decryptor = RealtimeAudioDecryptor(key, JVM_CIPHER)

        // Bytes 0..3 are not authenticated (AudioStream.kt:131): a changed sequence still opens.
        val sequenceChanged = AUDIO.copyOf().also { it[3] = 9 }
        assertThat(decryptor.open(sequenceChanged)?.sequence).isEqualTo(9)
        for (index in listOf(4, 11, 12, AUDIO.size - 9, AUDIO.size - 1)) {
            val tampered = AUDIO.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThat(decryptor.open(tampered)).isNull()
        }
    }

    @Test
    fun `short packets and other keys are dropped`() {
        assertThat(RealtimeAudioDecryptor(key, JVM_CIPHER).open(AUDIO, 0, AudioWire.MIN_PACKET_BYTES - 1)).isNull()
        assertThat(RealtimeAudioDecryptor(ByteArray(32), JVM_CIPHER).open(AUDIO)).isNull()
        assertFailure { RealtimeAudioDecryptor(key, JVM_CIPHER).open(AUDIO, 1, AUDIO.size) }
            .isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `a packet with an empty payload opens`() {
        // The smallest packet: header, tag, tail, sealed here by BouncyCastle's engine.
        val header = hex("80600001 00000400 00000000")
        val tail = hex("0100000000000000")
        val wire = header + bouncyCastleSeal(key, ByteArray(4) + tail, header.copyOfRange(4, 12), ByteArray(0)) + tail

        assertThat(RealtimeAudioDecryptor(key, JVM_CIPHER).open(wire)?.payload()).isNotNull().isEmpty()
    }

    @Test
    fun `buffered frames split at every byte keep payload and order`() {
        val reader = BufferedAudioReader(key, JVM_CIPHER)
        val wire = BUFFERED + BUFFERED

        val packets = wire.indices.flatMap { reader.feed(wire, it, 1) }

        assertThat(packets.map { it.payload().toHex() }).containsExactly("216c4f5510", "216c4f5510")
        assertThat(packets.first().timestamp).isEqualTo(0xFFFFFC00L) // BufferedAudioStreamTest.kt:212-215
    }

    @Test
    fun `a buffered frame length below the minimum ends the stream for good`() {
        // BufferedAudioStreamTest.kt:227-238 writes a length of 1.
        val reader = BufferedAudioReader(key, JVM_CIPHER)

        assertFailure { reader.feed(hex("0001")) }.isInstanceOf<MediaStreamException>()
        assertFailure { reader.feed(BUFFERED) }.isInstanceOf<IllegalStateException>()
    }

    @Test
    fun `the smallest buffered frame length is accepted and its empty payload dropped`() {
        val reader = BufferedAudioReader(key, JVM_CIPHER)
        val header = hex("80600001 00000400 00000000")
        val tail = hex("0100000000000000")
        val packet = header + bouncyCastleSeal(key, ByteArray(4) + tail, header.copyOfRange(4, 12), ByteArray(0)) + tail
        assertThat(packet.size + 2).isEqualTo(BufferedAudioReader.MIN_FRAME_BYTES)

        assertThat(reader.feed(hex("0026") + packet)).isEmpty()
        assertFailure { BufferedAudioReader(key, JVM_CIPHER).feed(hex("0025")) }.isInstanceOf<MediaStreamException>()
        assertThat(reader.feed(BUFFERED)).hasSize(1)
    }

    @Test
    fun `buffered frames that are not plain RTP v2 or do not open are dropped and the stream goes on`() {
        val reader = BufferedAudioReader(key, JVM_CIPHER)
        // BufferedAudioStreamTest.kt:217-223
        val dropped =
            listOf(0x40, 0x81, 0x90, 0xA0).map { first -> BUFFERED.copyOf().also { it[2] = first.toByte() } } +
                BUFFERED.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() } +
                BUFFERED.copyOf().also { it[it.size - 9] = (it[it.size - 9].toInt() xor 1).toByte() }

        for (frame in dropped) assertThat(reader.feed(frame)).isEmpty()
        assertThat(BufferedAudioReader(ByteArray(32), JVM_CIPHER).feed(BUFFERED)).isEmpty()
        assertThat(reader.feed(BUFFERED).single().sequence).isEqualTo(7)
    }

    @Test
    fun `microphone packets match the bytes Python seals for the DiPlay test inputs`() {
        val sealer = MicrophonePacketSealer(key, JVM_CIPHER, payloadType = 100)

        assertThat(sealer.seal(hex("28010203"), samples = 480).toHex())
            .isEqualTo("8064000000000000000000004b8bd515ea51d05aa90496879d7810872a63f1890000000000000000")
        assertThat(sealer.seal(hex("f8003142"), samples = 480).toHex())
            .isEqualTo("80640001000001e00000000098d571271f02d0b840ddafff0e525e44f76662720100000000000000")
    }

    @Test
    fun `the microphone sequence wraps at 16 bits`() {
        val sealer = MicrophonePacketSealer(key, JVM_CIPHER, payloadType = 100)
        repeat(0x10000) { sealer.seal(ByteArray(0), samples = 1) }

        val packet = sealer.seal(ByteArray(0), samples = 1)

        assertThat(packet.copyOfRange(2, 8).toHex()).isEqualTo("000000010000")
        assertThat(packet.copyOfRange(packet.size - 8, packet.size).toHex()).isEqualTo("0000010000000000")
    }

    @Test
    fun `little-endian PCM becomes big-endian`() {
        // MicrophonePacketizerTest.kt:45-52
        assertThat(MicrophonePacketSealer.toWirePcm(hex("34127856")).toHex()).isEqualTo("12345678")
        assertThat(MicrophonePacketSealer.toWirePcm(hex("341201")).toHex()).isEqualTo("123401")
    }

    private companion object {
        val AUDIO = hex("80600007fffffc0011223344c0003c3a345861ad4832d942e73962c90586a7e6eb0708090a0b0c0d0e")
        val BUFFERED = hex("002b80600007fffffc0011223344c0003c3a345861ad4832d942e73962c90586a7e6eb0708090a0b0c0d0e")
    }
}

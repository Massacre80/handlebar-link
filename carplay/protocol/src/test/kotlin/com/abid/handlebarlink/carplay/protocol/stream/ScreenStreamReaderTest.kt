package com.abid.handlebarlink.carplay.protocol.stream

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.protocol.hex
import com.abid.handlebarlink.carplay.protocol.pairing.JVM_CIPHER
import com.abid.handlebarlink.carplay.protocol.runWithoutSuspending
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test

/**
 * The screen stream against frames sealed by Python's `cryptography` from DiPlay's layout
 * (src/test/resources/streams/generate_vectors.py; ScreenStream.kt:83-125, 160-170) and against DiPlay's own
 * test literals (ScreenCodecTest.kt, ScreenStreamPeerTest.kt).
 */
class ScreenStreamReaderTest {
    private val key = ByteArray(32) { it.toByte() } // ScreenStreamPeerTest.kt:24

    private fun reader() = ScreenStreamReader(key, JVM_CIPHER)

    @Test
    fun `sealed pictures and the config between them come out in order with their frame times`() {
        val frames = reader().feed(FRAME_A + CONFIG + FRAME_B)

        assertThat(frames).hasSize(3)
        val (a, config, b) = frames
        assertThat(a.isConfig).isFalse()
        assertThat(a.senderNanos).isEqualTo(3_250_000_000L) // 3.25 s, ScreenCodecTest.kt:69-72
        assertThat(a.payload.toHex()).isEqualTo("00000001651100000001419a02")
        assertThat(config.isConfig).isTrue()
        assertThat((config as ScreenFrame.Config).codec).isEqualTo(VideoCodec.H264)
        assertThat(config.payload.toHex()).isEqualTo(AVCC.toHex())
        assertThat(b.senderNanos - a.senderNanos).isEqualTo(16_666_666L) // ScreenCodecTest.kt:74-76
        assertThat(b.payload.toHex()).isEqualTo("0000000141")
    }

    @Test
    fun `bytes arriving one at a time give the same frames`() {
        val reader = reader()
        val wire = FRAME_A + CONFIG + FRAME_B

        val frames = wire.indices.flatMap { reader.feed(wire, it, 1) }

        assertThat(frames.map { it.payload.toHex() })
            .containsExactly("00000001651100000001419a02", AVCC.toHex(), "0000000141")
        assertThat(reader.decryptedPictures).isEqualTo(2L)
    }

    @Test
    fun `next reads through a socket-style read function and returns null at the end`() {
        val reader = reader()
        val wire = FRAME_A + CONFIG + FRAME_B
        var at = 0
        val read: suspend (ByteArray) -> Int = { buffer ->
            if (at == wire.size) {
                -1
            } else {
                val n = minOf(7, wire.size - at, buffer.size)
                wire.copyInto(buffer, 0, at, at + n)
                at += n
                n
            }
        }

        val frames = runWithoutSuspending { generateSequenceOf(reader, read) }

        assertThat(frames.map { it.isConfig }).containsExactly(false, true, false)
    }

    @Test
    fun `an end in the middle of a message is a normal end`() {
        val reader = reader()
        var sent = false
        val read: suspend (ByteArray) -> Int = { buffer ->
            if (sent) {
                -1
            } else {
                sent = true
                FRAME_A.copyInto(buffer, 0, 0, 100)
                100
            }
        }

        assertThat(runWithoutSuspending { reader.next(read) }).isNull()
    }

    @Test
    fun `a picture sealed under the wrong counter ends the stream for good`() {
        val reader = reader()
        reader.feed(FRAME_A + CONFIG)

        assertFailure { reader.feed(FRAME_B_WRONG_COUNTER) }.isInstanceOf<MediaStreamException>()
        assertFailure { reader.feed(FRAME_B) }.isInstanceOf<IllegalStateException>()
    }

    @Test
    fun `every header byte is authenticated`() {
        val tampered = FRAME_A.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }

        assertFailure { reader().feed(tampered) }.isInstanceOf<MediaStreamException>()
    }

    @Test
    fun `a picture shorter than the tag is skipped without advancing the counter`() {
        // ScreenStreamPeerTest.kt:76-84: header(0, 8) + 00 00 00 04 01 02 03 04.
        val short = header(opcode = 0, bodySize = 8) + hex("00000004 01020304")

        val frames = reader().feed(short + FRAME_A)

        assertThat(frames.single().payload.toHex()).isEqualTo("00000001651100000001419a02")
    }

    @Test
    fun `a message with an unknown opcode is skipped`() {
        val frames = reader().feed(header(opcode = 2, bodySize = 5) + ByteArray(5) + FRAME_A)

        assertThat(frames.single().isConfig).isFalse()
    }

    @Test
    fun `a body over 8 MiB ends the stream as soon as its header arrives`() {
        assertFailure { reader().feed(header(opcode = 0, bodySize = ScreenStreamReader.MAX_BODY_BYTES + 1)) }
            .isInstanceOf<MediaStreamException>()
    }

    @Test
    fun `a body size with the top bit set ends the stream`() {
        // DiPlay reads it as a negative Int and stops (ScreenStream.kt:84-86, 129).
        val header = header(opcode = 0, bodySize = 0).also { hex("ffffffff").copyInto(it) }

        assertFailure { reader().feed(header) }.isInstanceOf<MediaStreamException>()
    }

    @Test
    fun `a body of exactly 8 MiB is accepted`() {
        val reader = reader()

        assertThat(reader.feed(header(opcode = 2, bodySize = ScreenStreamReader.MAX_BODY_BYTES))).isEmpty()
        assertThat(reader.feed(ByteArray(ScreenStreamReader.MAX_BODY_BYTES))).isEmpty()
        assertThat(reader.feed(FRAME_A)).hasSize(1)
    }

    @Test
    fun `the DiPlay avcC record is H264 and an empty config is H265 as DiPlay decides`() {
        // ScreenStreamPeerTest.kt:70
        val avcc = hex("01 64 00 1f ff e1 00 04 67 64 00 1f")

        assertThat(ScreenStreamReader.config(avcc).codec).isEqualTo(VideoCodec.H264)
        assertThat(ScreenStreamReader.config(avcc).payload).isSameInstanceAs(avcc)
        assertThat(ScreenStreamReader.config(ByteArray(0)).codec).isEqualTo(VideoCodec.H265)
        assertThat(ScreenStreamReader.config(hex("01 64 00 1f ff e0 00 04 67")).codec).isEqualTo(VideoCodec.H265)
    }

    @Test
    fun `a record after an avcC or hvcC box type is cut out of the config body`() {
        val boxed = hex("00000010") + "avcC".encodeToByteArray() + hex("0164001f")
        val hevc = hex("00000010") + "hvcC".encodeToByteArray() + hex("01")

        assertThat(ScreenStreamReader.config(boxed).payload.toHex()).isEqualTo("0164001f")
        assertThat(ScreenStreamReader.config(boxed).codec).isEqualTo(VideoCodec.H264)
        assertThat(ScreenStreamReader.config(hevc).codec).isEqualTo(VideoCodec.H265)
        assertThat(ScreenStreamReader.config(hevc).payload.toHex()).isEqualTo("01")
    }

    @Test
    fun `the avcC record yields its first SPS and PPS`() {
        // Read by hand from the record: one SPS of 4 bytes at 8, one PPS of 4 bytes after the count at 12.
        val sets = ScreenFrame.Config(VideoCodec.H264, AVCC).avcParameterSets()

        assertThat(sets.sps.toHex()).isEqualTo("6764001f")
        assertThat(sets.pps.toHex()).isEqualTo("68ee3c80")
        assertThat(ScreenFrame.Config(VideoCodec.H264, hex("0164")).avcParameterSets().sps).isEmpty()
        assertThat(
            ScreenFrame.Config(VideoCodec.H264, hex("0164001fffe100046764001f")).avcParameterSets().pps,
        ).isEmpty()
        assertThat(ScreenFrame.Config(VideoCodec.H264, hex("0164001fffe1 00ff 67")).avcParameterSets().sps).isEmpty()
    }

    @Test
    fun `valid length prefixes become Annex B in place`() {
        // ScreenCodecTest.kt:10-25
        val payload = hex("00000002 4001 00000003 420102")

        val converted = ScreenStreamReader.lengthPrefixedToAnnexB(payload)

        assertThat(converted).isSameInstanceAs(payload)
        assertThat(converted.toHex()).isEqualTo("00000001400100000001420102")
    }

    @Test
    fun `malformed or overflowing lengths leave the payload untouched`() {
        // ScreenCodecTest.kt:27-52
        val malformed =
            listOf(
                hex("00000005 4001"),
                hex("7fffffff 00000001"),
                hex("80000000 00000001"),
                hex("ffffffff 00000001"),
                hex("00000000 00000001"),
                // A zero length would otherwise tile this payload (ScreenStream.kt:187 rejects length <= 0).
                hex("00000000 00000001 41"),
                hex("00000001 41 00"),
                hex("000000"),
                hex("00000001 4142"),
            )
        for (payload in malformed) {
            val original = payload.toHex()
            assertThat(ScreenStreamReader.lengthPrefixedToAnnexB(payload)).isSameInstanceAs(payload)
            assertThat(payload.toHex()).isEqualTo(original)
        }
    }

    @Test
    fun `a NAL length that exactly fills the payload is converted`() {
        // ScreenCodecTest.kt:54-65
        assertThat(ScreenStreamReader.lengthPrefixedToAnnexB(hex("00000004 41020304")).toHex())
            .isEqualTo("0000000141020304")
    }

    @Test
    fun `the frame time is read from header bytes 8 to 15`() {
        // ScreenCodecTest.kt:67-78
        val header = ByteArray(128)
        val raw = (3L shl 32) or 0x4000_0000L
        for (i in 0 until 8) header[8 + i] = (raw ushr (8 * i)).toByte()

        assertThat(ScreenStreamReader.senderNanos(header)).isEqualTo(3_250_000_000L)
        assertThat(ScreenStreamReader.senderNanos(ByteArray(12))).isEqualTo(0L)
    }

    @Test
    fun `slices outside the array are refused`() {
        assertFailure { reader().feed(ByteArray(4), 2, 3) }.isInstanceOf<IllegalArgumentException>()
        assertFailure { reader().feed(ByteArray(4), -1, 1) }.isInstanceOf<IllegalArgumentException>()
    }

    private suspend fun generateSequenceOf(
        reader: ScreenStreamReader,
        read: suspend (ByteArray) -> Int,
    ): List<ScreenFrame> {
        val frames = mutableListOf<ScreenFrame>()
        while (true) frames += reader.next(read) ?: return frames
    }

    private fun header(
        opcode: Int,
        bodySize: Int,
    ) = ByteArray(128).also {
        // ScreenStreamPeerTest.kt:35-39, with all four size bytes.
        for (i in 0 until 4) it[i] = (bodySize ushr (8 * i)).toByte()
        it[4] = opcode.toByte()
    }

    private companion object {
        val AVCC = hex("0164001fffe100046764001f01000468ee3c80")

        // generate_vectors.py: frame A (counter 0), the config, frame B (counter 1), frame B under counter 2.
        const val FILLER =
            "101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f" +
                "707172737475767778797a7b7c7d7e7f"
        val FRAME_A =
            hex(
                "1d000000000506070000004003000000" + FILLER +
                    "18b84233c8f7a6d113621dfbad19d85fa7a2e2f5857ea3a5265859558d",
            )
        val CONFIG = hex("13000000010506070000000000000000" + FILLER + "0164001fffe100046764001f01000468ee3c80")
        val FRAME_B = hex("15000000000506074444444403000000" + FILLER + "9f57f35ef4f2fda0fbab65a9e7ab46dabc5d9776ee")
        val FRAME_B_WRONG_COUNTER =
            hex("15000000000506074444444403000000" + FILLER + "a9a3fe54a67a76f9e8ee09e5d75cb6bc055d63d0b7")
    }
}

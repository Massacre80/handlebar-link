package com.abid.handlebarlink.carplay.protocol.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

/**
 * The decoders' configuration bytes, against the standards (ISO/IEC 14496-3 AudioSpecificConfig, ISO/IEC 13818-7 ADTS,
 * RFC 7845 OpusHead), DiPlay's test literals (MediaCodecSupportTest.kt:66-79) and Python's int.to_bytes.
 */
class AudioDecoderConfigTest {
    @Test
    fun `AAC-LC gets its AudioSpecificConfig and ADTS headers`() {
        // ISO/IEC 14496-3: object type 2, frequency index 3 (48 kHz) or 4 (44.1 kHz), 2 channels.
        assertThat(AudioDecoderConfig.aacSpecificConfig(48_000, 2).toHex()).isEqualTo("1190")
        assertThat(AudioDecoderConfig.aacSpecificConfig(44_100, 2).toHex()).isEqualTo("1210")
        assertThat(AudioDecoderConfig.aacFrequencyIndex(12_345)).isEqualTo(3)
        // ISO/IEC 13818-7: sync, MPEG-4, no CRC, LC, 48 kHz, stereo, frame length 107 (100 + 7), buffer fullness 0x7ff.
        assertThat(
            checkNotNull(AudioDecoderConfig.adtsFrame(ByteArray(100), 48_000, 2)).copyOf(7).toHex(),
        ).isEqualTo("fff14c800d7ffc")
    }

    @Test
    fun `the largest access unit still fits ADTS's 13-bit length and one byte more is refused`() {
        // DiPlay MediaCodecSupportTest.kt:66-79.
        val header = checkNotNull(AudioDecoderConfig.adtsFrame(ByteArray(8_184), 48_000, 2)).copyOf(7)
        val length =
            ((header[3].toInt() and 0x3) shl 11) or ((header[4].toInt() and 0xff) shl 3) or
                ((header[5].toInt() and 0xe0) ushr 5)
        assertThat(length).isEqualTo(0x1fff)
        assertThat(AudioDecoderConfig.adtsFrame(ByteArray(8_185), 48_000, 2)).isNull()
    }

    @Test
    fun `Opus gets an OpusHead and its delays`() {
        // RFC 7845 §5.1 (19 bytes): magic, version 1, 1 channel, pre-skip 312, 48 000 Hz, gain 0, mapping family 0.
        assertThat(AudioDecoderConfig.opusHead(48_000, 1).toHex()).isEqualTo("4f707573486561640101380180bb0000000000")
        val (delay, preRoll) = AudioDecoderConfig.opusDelays()
        // 6.5 ms and 80 ms in nanoseconds, little-endian (AndroidMediaSink.kt:2847-2848; Python int.to_bytes).
        assertThat(delay.toHex()).isEqualTo("a02e630000000000")
        assertThat(preRoll.toHex()).isEqualTo("00b4c40400000000")
    }

    @Test
    fun `big-endian PCM becomes little-endian in place and an odd last byte stays`() {
        assertThat(AudioDecoderConfig.swapPcm16(hex("12345678")).toHex()).isEqualTo("34127856")
        assertThat(AudioDecoderConfig.swapPcm16(hex("123401")).toHex()).isEqualTo("341201")
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

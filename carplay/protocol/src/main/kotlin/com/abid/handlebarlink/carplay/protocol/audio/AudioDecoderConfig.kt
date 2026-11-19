package com.abid.handlebarlink.carplay.protocol.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bytes a platform decoder needs around CarPlay's audio payloads (DiPlay AndroidMediaSink.kt:2062-2104, 2314-2364,
 * 2792-2799; MediaCodecSupport.kt:121-168): AAC-LC's AudioSpecificConfig (ISO/IEC 14496-3) and ADTS header
 * (ISO/IEC 13818-7), Opus's OpusHead (RFC 7845 §5.1) and delays, and PCM's byte order.
 */
public object AudioDecoderConfig {
    private const val ADTS_HEADER_BYTES = 7

    /** An access unit larger than this does not fit ADTS's 13-bit frame length with its 7-byte header. */
    public const val MAX_ADTS_ACCESS_UNIT_BYTES: Int = 0x1FFF - ADTS_HEADER_BYTES

    /** Shorter Opus payloads are skipped (AndroidMediaSink.kt:2846). */
    public const val MIN_OPUS_PACKET_BYTES: Int = 4

    private const val AAC_OBJECT_TYPE_LC = 2
    private const val MAX_CHANNEL_CONFIG = 7
    private const val FREQUENCY_48K = 3
    private const val OPUS_HEAD_BYTES = 19
    private const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
    private const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
    private val FREQUENCIES =
        listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)

    /** The MPEG-4 sampling frequency index of ADTS and AudioSpecificConfig; 48 kHz's for an unknown rate. */
    public fun aacFrequencyIndex(sampleRate: Int): Int =
        FREQUENCIES.indexOf(sampleRate).takeIf { it >= 0 } ?: FREQUENCY_48K

    /** The 2-byte AAC-LC AudioSpecificConfig, the decoder's `csd-0` (AndroidMediaSink.kt:2314-2320). */
    public fun aacSpecificConfig(
        sampleRate: Int,
        channels: Int,
    ): ByteArray {
        val value =
            (AAC_OBJECT_TYPE_LC shl 11) or (aacFrequencyIndex(sampleRate) shl 7) or
                (channels.coerceIn(1, MAX_CHANNEL_CONFIG) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    /**
     * One raw AAC-LC access unit (what CarPlay sends on stream 102, AndroidMediaSink.kt:2376-2399) behind an ADTS
     * header, or null when it is too large for ADTS (MediaCodecSupport.kt:121-137, CarLink PR #1294).
     */
    public fun adtsFrame(
        accessUnit: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): ByteArray? {
        if (accessUnit.size > MAX_ADTS_ACCESS_UNIT_BYTES) return null
        val frequency = aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, MAX_CHANNEL_CONFIG)
        val length = accessUnit.size + ADTS_HEADER_BYTES
        val header =
            byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(),
                ((1 shl 6) or (frequency shl 2) or (channelConfig ushr 2)).toByte(),
                (((channelConfig and 0x3) shl 6) or ((length ushr 11) and 0x3)).toByte(),
                ((length ushr 3) and 0xFF).toByte(),
                (((length and 0x7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        return header + accessUnit
    }

    /** A minimal OpusHead for the stream, the decoder's `csd-0`: pre-skip 312 (AndroidMediaSink.kt:2339-2352). */
    public fun opusHead(
        sampleRate: Int,
        channels: Int,
    ): ByteArray {
        val head = ByteArray(OPUS_HEAD_BYTES)
        "OpusHead".encodeToByteArray().copyInto(head, 0)
        head[8] = 1
        head[9] = channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        for (i in 0 until 4) head[12 + i] = (sampleRate ushr (8 * i)).toByte()
        return head
    }

    /** `csd-1`, the codec delay, and `csd-2`, the seek pre-roll, as little-endian nanoseconds (:2354-2364). */
    public fun opusDelays(): Pair<ByteArray, ByteArray> =
        littleEndian(OPUS_CODEC_DELAY_NANOS) to littleEndian(OPUS_SEEK_PRE_ROLL_NANOS)

    private fun littleEndian(value: Long): ByteArray =
        ByteBuffer
            .allocate(Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(value)
            .array()

    /** CarPlay's big-endian 16-bit PCM as little-endian, in place; an odd last byte stays (:2792-2799). */
    public fun swapPcm16(pcm: ByteArray): ByteArray {
        var index = 0
        while (index + 1 < pcm.size) {
            val first = pcm[index]
            pcm[index] = pcm[index + 1]
            pcm[index + 1] = first
            index += 2
        }
        return pcm
    }
}

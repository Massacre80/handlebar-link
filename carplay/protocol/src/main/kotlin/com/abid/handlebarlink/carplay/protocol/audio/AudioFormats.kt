package com.abid.handlebarlink.carplay.protocol.audio

import com.abid.handlebarlink.carplay.protocol.plist.Plist

/** How an audio stream's payload is coded (AudioStream.kt:12). */
public enum class AudioCodec {
    /** AAC-LC access units (RFC 3640 in RTP; MediaCodecSupport.kt:139-150). */
    AAC_LC,

    /** Opus packets. */
    OPUS,

    /** 16-bit PCM. Big-endian on the wire (MicrophonePacketizer.kt:128). */
    LPCM,
}

/**
 * The decode format of an audio stream SETUP's `audioFormat` bits (DiPlay AudioStreamCodec.fromFormatBits,
 * AudioStream.kt:221-262; MicrophonePacketizer.kt:48-54).
 */
public data class AudioFormat(
    /** The codec. */
    val codec: AudioCodec,
    /** The rate the payload decodes at: Opus always 48 kHz. */
    val sampleRate: Int,
    /** Channel count. */
    val channels: Int,
    /** The rate the RTP timestamps count at (Opus: 16, 24 or 48 kHz by its bit; AudioStream.kt:20-24). */
    val rtpClockRate: Int,
) {
    /** Decoding of `audioFormat` bits. */
    public companion object {
        private const val AAC_LC_44K_STEREO = 0x400000L
        private const val AAC_LC_48K_STEREO = 0x800000L
        private const val OPUS_16K = 0x10000000L
        private const val OPUS_24K = 0x20000000L
        private const val OPUS_48K = 0x40000000L
        private const val OPUS_ANY = OPUS_16K or OPUS_24K or OPUS_48K
        private const val OPUS_DECODE_RATE = 48_000

        private val PCM =
            mapOf(
                0x4L to (8_000 to 1),
                0x8L to (8_000 to 2),
                0x10L to (16_000 to 1),
                0x20L to (16_000 to 2),
                0x40L to (24_000 to 1),
                0x80L to (24_000 to 2),
                0x100L to (32_000 to 1),
                0x200L to (32_000 to 2),
                0x400L to (44_100 to 1),
                0x800L to (44_100 to 2),
                0x4000L to (48_000 to 1),
                0x8000L to (48_000 to 2),
            )

        /**
         * The format of [bits], in DiPlay's order (AudioStream.kt:223-242): any Opus bit → Opus mono at 48 kHz;
         * else an AAC-LC bit → stereo AAC at 48 kHz (if its bit is set) or 44.1 kHz; else exactly one PCM bit
         * → that PCM; anything else → PCM 44.1 kHz stereo.
         */
        public fun fromBits(bits: Long): AudioFormat {
            val pcm = PCM[bits]
            return when {
                bits and OPUS_ANY != 0L -> {
                    AudioFormat(AudioCodec.OPUS, OPUS_DECODE_RATE, 1, opusClockRate(bits))
                }

                bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO) != 0L -> {
                    val rate = if (bits and AAC_LC_48K_STEREO != 0L) 48_000 else 44_100
                    AudioFormat(AudioCodec.AAC_LC, rate, 2, rate)
                }

                pcm != null -> {
                    AudioFormat(AudioCodec.LPCM, pcm.first, pcm.second, pcm.first)
                }

                else -> {
                    AudioFormat(AudioCodec.LPCM, 44_100, 2, 44_100)
                }
            }
        }

        /**
         * The Opus format's RTP clock: 48, 24 or 16 kHz, highest bit first; 48 kHz without one
         * (MicrophonePacketizer.kt:48-54).
         */
        public fun opusClockRate(bits: Long): Int =
            when {
                bits and OPUS_48K != 0L -> 48_000
                bits and OPUS_24K != 0L -> 24_000
                bits and OPUS_16K != 0L -> 16_000
                else -> OPUS_DECODE_RATE
            }
    }
}

/**
 * What /info declares about audio (DiPlay AirPlayInfoPlist.kt:51-55, 105-177; spec D §4). DiPlay notes the
 * iPhone ends a session whose /info lacks them (AirPlayInfoPlist.kt:4-7). Omit both keys only when the
 * receiver plays no audio at all, with the matching feature bits (AirPlayInfoPlist.kt:17-19, 28-29).
 */
public object AudioCapabilities {
    /**
     * `audioLatencies`: nine entries `{type, inputLatencyMicros 0, outputLatencyMicros 0[, audioType]}` in
     * DiPlay's order (AirPlayInfoPlist.kt:105-120).
     */
    public fun audioLatencies(): Plist.Array =
        Plist.Array(
            listOf(
                100 to null,
                100 to "default",
                100 to "media",
                100 to "telephony",
                100 to "speechRecognition",
                100 to "alert",
                101 to null,
                101 to "default",
                102 to "default",
            ).map { (type, audioType) ->
                val entry =
                    linkedMapOf<String, Any>(
                        "type" to type,
                        "inputLatencyMicros" to 0L,
                        "outputLatencyMicros" to 0L,
                    )
                if (audioType != null) entry["audioType"] = audioType
                Plist.of(entry)
            },
        )

    /**
     * `audioFormats` (AirPlayInfoPlist.kt:122-177), with DiPlay's defaults: 48 kHz music only
     * (AirPlayConfig.kt:75), no microphone (:78), Opus offered for the microphone (:84), no buffered audio
     * (:95), speech with 8 and 16 kHz (:100), calls not limited to the vocoder rates (:107).
     *
     * @param musicRates the entertainment rates offered, a non-empty subset of 44 100 and 48 000
     *   (EntertainmentAudioCapabilities.kt:6).
     * @param microphone whether `audioInputFormats` are offered on the recording streams.
     * @param microphoneOpus whether those input formats include Opus.
     * @param mainBuffered whether the buffered stream (103, AAC-LC) is declared.
     */
    public fun audioFormats(
        musicRates: Set<Int> = setOf(48_000),
        microphone: Boolean = false,
        microphoneOpus: Boolean = true,
        mainBuffered: Boolean = false,
        speechWithout8And16Khz: Boolean = false,
        callAtCallRate: Boolean = false,
    ): Plist.Array {
        require(musicRates.isNotEmpty() && musicRates.all { it == 44_100 || it == 48_000 }) {
            "Unsupported music rates: $musicRates"
        }
        val pcm = 0x3FC or rateBits(musicRates, at44 = 0xC00, at48 = 0xC000)
        val speech = if (speechWithout8And16Khz) pcm and 0x3C.inv() else pcm
        val pcmMono = 0x154 or rateBits(musicRates, at44 = 0x400, at48 = 0x4000)
        val opus = 0x70000000
        val aacLc = rateBits(musicRates, at44 = 0x400000, at48 = 0x800000)
        val telephonyOutput = if (callAtCallRate) CALL_RATES_MONO else pcmMono or opus
        val pcmInput = if (microphone) pcmMono else null
        val wirelessInput = if (microphone) wirelessInput(pcmMono, microphoneOpus) else null
        val telephonyInput = if (callAtCallRate && microphone) CALL_RATES_MONO else wirelessInput
        val formats =
            listOf(
                format(100, "compatibility", pcm, pcmInput),
                format(101, "compatibility", pcm),
                format(100, "default", speech or opus, wirelessInput),
                format(100, "alert", speech or opus),
                format(100, "media", pcm),
                format(100, "telephony", telephonyOutput, telephonyInput),
                format(100, "speechRecognition", pcmMono or opus, wirelessInput),
                format(101, "default", speech or opus),
                format(102, "media", aacLc),
            ) + if (mainBuffered) listOf(format(103, "media", aacLc)) else emptyList()
        return Plist.Array(formats)
    }

    /** [at44] if 44.1 kHz music is offered, or [at48] if 48 kHz is, or both (AirPlayInfoPlist.kt:144-152). */
    private fun rateBits(
        rates: Set<Int>,
        at44: Int,
        at48: Int,
    ): Int = (if (44_100 in rates) at44 else 0) or (if (48_000 in rates) at48 else 0)

    /** PCM mono at the vocoder rates 8, 16, 24 and 32 kHz (AirPlayInfoPlist.kt:153-154). */
    private const val CALL_RATES_MONO = 0x154

    /** AirPlayInfoPlist.kt:155-158: Opus is offered to the microphone whenever an encoder is usable. */
    private fun wirelessInput(
        pcmMono: Int,
        opus: Boolean,
    ): Int = if (opus) pcmMono or 0x70000000 else pcmMono

    private fun format(
        type: Int,
        audioType: String,
        output: Int,
        input: Int? = null,
    ): Plist {
        val entry = linkedMapOf<String, Any>("type" to type, "audioType" to audioType, "audioOutputFormats" to output)
        if (input != null) entry["audioInputFormats"] = input
        return Plist.of(entry)
    }
}

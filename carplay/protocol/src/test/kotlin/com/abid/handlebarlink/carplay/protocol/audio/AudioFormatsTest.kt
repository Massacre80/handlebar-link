package com.abid.handlebarlink.carplay.protocol.audio

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import org.junit.Test

/** Audio format bits and the /info audio declarations, against DiPlay's test literals and its code read by hand. */
class AudioFormatsTest {
    @Test
    fun `Opus decodes at 48 kHz but keeps the negotiated RTP clock`() {
        // AudioStreamCodecTest.kt:7-15
        for ((bits, clock) in listOf(0x10000000L to 16_000, 0x20000000L to 24_000, 0x40000000L to 48_000)) {
            assertThat(AudioFormat.fromBits(bits)).isEqualTo(AudioFormat(AudioCodec.OPUS, 48_000, 1, clock))
        }
    }

    @Test
    fun `other formats count at their sample rate`() {
        // AudioStreamCodecTest.kt:17-21
        assertThat(AudioFormat.fromBits(0x800000L)).isEqualTo(AudioFormat(AudioCodec.AAC_LC, 48_000, 2, 48_000))
        assertThat(AudioFormat.fromBits(0x400000L)).isEqualTo(AudioFormat(AudioCodec.AAC_LC, 44_100, 2, 44_100))
        assertThat(AudioFormat.fromBits(0x10L)).isEqualTo(AudioFormat(AudioCodec.LPCM, 16_000, 1, 16_000))
    }

    @Test
    fun `PCM needs exactly one bit and anything unknown is 44_1 kHz stereo PCM`() {
        // AudioStream.kt:239-261
        assertThat(AudioFormat.fromBits(0x8000L)).isEqualTo(AudioFormat(AudioCodec.LPCM, 48_000, 2, 48_000))
        assertThat(AudioFormat.fromBits(0x40L)).isEqualTo(AudioFormat(AudioCodec.LPCM, 24_000, 1, 24_000))
        assertThat(AudioFormat.fromBits(0x4L)).isEqualTo(AudioFormat(AudioCodec.LPCM, 8_000, 1, 8_000))
        assertThat(AudioFormat.fromBits(0x4010L)).isEqualTo(AudioFormat(AudioCodec.LPCM, 44_100, 2, 44_100))
        assertThat(AudioFormat.fromBits(0L)).isEqualTo(AudioFormat(AudioCodec.LPCM, 44_100, 2, 44_100))
        // Opus wins over AAC, AAC over PCM.
        assertThat(AudioFormat.fromBits(0x40800000L).codec).isEqualTo(AudioCodec.OPUS)
        assertThat(AudioFormat.fromBits(0x808000L).codec).isEqualTo(AudioCodec.AAC_LC)
    }

    @Test
    fun `the Opus clock follows the highest Opus bit`() {
        // MicrophonePacketizerTest.kt:58-61
        assertThat(AudioFormat.opusClockRate(0x20000000L)).isEqualTo(24_000)
        assertThat(AudioFormat.opusClockRate(0x40000000L)).isEqualTo(48_000)
        assertThat(AudioFormat.opusClockRate(0x10000000L)).isEqualTo(16_000)
        assertThat(AudioFormat.opusClockRate(0L)).isEqualTo(48_000)
        assertThat(AudioFormat.opusClockRate(0x30000000L)).isEqualTo(24_000)
    }

    @Test
    fun `the default formats are the ones DiPlay declares at 48 kHz without a microphone`() {
        // AirPlayInfoPlist.kt:146-172 by hand: pcm 0x3FC or 0xC000, pcmMono 0x154 or 0x4000, opus 0x70000000,
        // AAC-LC 48 kHz 0x800000.
        val expected =
            listOf(
                Triple(100, "compatibility", 0xC3FC),
                Triple(101, "compatibility", 0xC3FC),
                Triple(100, "default", 0x7000C3FC),
                Triple(100, "alert", 0x7000C3FC),
                Triple(100, "media", 0xC3FC),
                Triple(100, "telephony", 0x70004154),
                Triple(100, "speechRecognition", 0x70004154),
                Triple(101, "default", 0x7000C3FC),
                Triple(102, "media", 0x800000),
            ).map { (type, audioType, output) ->
                Plist.dict("type" to type, "audioType" to audioType, "audioOutputFormats" to output)
            }

        assertThat(AudioCapabilities.audioFormats().items).isEqualTo(expected)
    }

    @Test
    fun `microphone inputs are advertised only when enabled`() {
        // AirPlayInfoPlistTest.kt:203-253
        fun entry(
            formats: Plist.Array,
            type: Int,
            audioType: String,
        ) = formats.items.map { it as Plist.Dict }.single {
            it.long("type") == type.toLong() &&
                it.text("audioType") == audioType
        }

        val without = AudioCapabilities.audioFormats()
        val with = AudioCapabilities.audioFormats(microphone = true)
        val withoutOpus = AudioCapabilities.audioFormats(microphone = true, microphoneOpus = false)

        assertThat(entry(without, 100, "telephony").entries.containsKey("audioInputFormats")).isFalse()
        for (audioType in listOf("default", "telephony", "speechRecognition")) {
            assertThat(entry(with, 100, audioType).long("audioInputFormats")).isEqualTo(0x70004154L)
            assertThat(entry(withoutOpus, 100, audioType).long("audioInputFormats")).isEqualTo(0x4154L)
        }
        assertThat(entry(with, 100, "compatibility").long("audioInputFormats")).isEqualTo(0x4154L)
        assertThat(entry(with, 100, "media").long("audioInputFormats")).isNull()
        assertThat(entry(withoutOpus, 100, "telephony").long("audioOutputFormats"))
            .isEqualTo(entry(with, 100, "telephony").long("audioOutputFormats"))
    }

    @Test
    fun `main alt and high audio types are declared, and buffered audio only when enabled`() {
        // AirPlayInfoPlistTest.kt:274-290; BufferedAudioStreamTest.kt:41 (AAC-LC 48 kHz stereo).
        fun types(formats: Plist.Array) = formats.items.map { (it as Plist.Dict).long("type") }.toSet()

        assertThat(types(AudioCapabilities.audioFormats())).isEqualTo(setOf(100L, 101L, 102L))
        val buffered = AudioCapabilities.audioFormats(mainBuffered = true).items.last() as Plist.Dict
        assertThat(buffered.long("type")).isEqualTo(103L)
        assertThat(buffered.long("audioOutputFormats")).isEqualTo(0x800000L)
    }

    @Test
    fun `both music rates, speech without 8 and 16 kHz, and calls at their own rate`() {
        // AirPlayInfoPlist.kt:146-161 by hand.
        val formats =
            AudioCapabilities
                .audioFormats(
                    musicRates = setOf(44_100, 48_000),
                    microphone = true,
                    speechWithout8And16Khz = true,
                    callAtCallRate = true,
                ).items
                .map { it as Plist.Dict }
        val byKey = formats.associateBy { it.long("type") to it.text("audioType") }

        assertThat(byKey.getValue(100L to "media").long("audioOutputFormats")).isEqualTo(0xCFFCL)
        assertThat(byKey.getValue(100L to "default").long("audioOutputFormats")).isEqualTo(0x7000CFC0L)
        assertThat(byKey.getValue(100L to "telephony").long("audioOutputFormats")).isEqualTo(0x154L)
        assertThat(byKey.getValue(100L to "telephony").long("audioInputFormats")).isEqualTo(0x154L)
        assertThat(byKey.getValue(100L to "speechRecognition").long("audioInputFormats")).isEqualTo(0x70004554L)
        assertThat(byKey.getValue(102L to "media").long("audioOutputFormats")).isEqualTo(0xC00000L)
        assertFailure {
            AudioCapabilities.audioFormats(
                musicRates = setOf(32_000),
            )
        }.isInstanceOf<IllegalArgumentException>()
        assertFailure {
            AudioCapabilities.audioFormats(
                musicRates = emptySet(),
            )
        }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `the nine latency entries come in DiPlay order with zero latencies`() {
        // AirPlayInfoPlist.kt:105-120
        val entries = AudioCapabilities.audioLatencies().items.map { it as Plist.Dict }

        assertThat(entries.map { it.long("type") to it.text("audioType") }).containsExactly(
            100L to null,
            100L to "default",
            100L to "media",
            100L to "telephony",
            100L to "speechRecognition",
            100L to "alert",
            101L to null,
            101L to "default",
            102L to "default",
        )
        assertThat(
            entries
                .first()
                .entries.keys
                .toList(),
        ).containsExactly("type", "inputLatencyMicros", "outputLatencyMicros")
        assertThat(entries.map { it.long("inputLatencyMicros") to it.long("outputLatencyMicros") }.toSet())
            .isEqualTo(setOf(0L to 0L))
    }
}

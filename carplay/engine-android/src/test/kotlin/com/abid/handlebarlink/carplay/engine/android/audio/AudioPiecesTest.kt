package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

/**
 * The pure parts of CarPlay's sound on Android: routes (DiPlay AudioChannelMapping.kt, AndroidMediaSink.kt:2322-2337),
 * buffer plans (MediaAudioBuffer.kt) and the underrun tuner.
 */
class AudioPiecesTest {
    @Test
    fun `each CarPlay audio type plays with DiPlay's usage and content type`() {
        val routes =
            listOf(
                100 to "telephony",
                100 to "speechrecognition",
                100 to "media",
                100 to "default",
                100 to "alert",
                100 to "compatibility",
                101 to "default",
                102 to "media",
                102 to "other",
                100 to "other",
            ).map { (type, audioType) ->
                AudioRoute.of(type, audioType).let { Triple(it.channel, it.usage, it.contentType) }
            }

        val speech = AudioAttributes.CONTENT_TYPE_SPEECH
        val navigation = Triple(AudioChannel.NAVIGATION, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, speech)
        val music = Triple(AudioChannel.MEDIA, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC)
        assertThat(routes).containsExactly(
            Triple(AudioChannel.PHONE, AudioAttributes.USAGE_VOICE_COMMUNICATION, speech),
            Triple(AudioChannel.ASSISTANT, AudioAttributes.USAGE_ASSISTANT, speech),
            music,
            navigation,
            navigation,
            navigation,
            navigation,
            music,
            music,
            navigation,
        )
    }

    @Test
    fun `focus gains and priorities follow DiPlay, and directions take no focus`() {
        val gains = listOf("media", "telephony", "speechrecognition", "default").map { AudioRoute.of(100, it) }
        assertThat(gains.map { it.focusGain }).containsExactly(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            null,
        )
        assertThat(gains.map { it.focusPriority }).containsExactly(3, 2, 1, 0)
    }

    @Test
    fun `speech at the output's own rate asks for the low-latency path, music and resampled speech do not`() {
        val directions = AudioRoute.of(100, "default")
        assertThat(directions.performanceMode(48_000, 48_000)).isEqualTo(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        assertThat(directions.performanceMode(24_000, 48_000)).isEqualTo(AudioTrack.PERFORMANCE_MODE_NONE)
        assertThat(directions.performanceMode(48_000, 0)).isEqualTo(AudioTrack.PERFORMANCE_MODE_NONE)
        assertThat(
            AudioRoute.of(100, "media").performanceMode(48_000, 48_000),
        ).isEqualTo(AudioTrack.PERFORMANCE_MODE_NONE)
    }

    @Test
    fun `music queues 300 ms in a 500 ms track`() {
        // 48 kHz stereo: 192 000 bytes a second (MediaAudioBuffer.kt:30-43, wireless default 300 ms + 200 ms headroom).
        val plan =
            PlaybackPlan.of(
                media = true,
                sampleRate = 48_000,
                channels = 2,
                minBufferBytes = 3_840,
                burstFrames = 192,
            )
        assertThat(
            plan,
        ).isEqualTo(PlaybackPlan(frameBytes = 4, capacityFrames = 24_000, startFrames = 14_400, initialFrames = 24_000))
    }

    @Test
    fun `speech keeps DiPlay's small buffer and works with what the start needs, in whole bursts`() {
        // Capacity max(4 x 3840, 16 KiB) = 16 384 bytes; start max(3840, 4 KiB) = 4096 bytes; mono: 2 bytes a frame.
        val plan =
            PlaybackPlan.of(
                media = false,
                sampleRate = 48_000,
                channels = 1,
                minBufferBytes = 3_840,
                burstFrames = 192,
            )
        // Start 2048 frames plus one 2 KiB write (1024 frames) = 3072, which is 16 bursts of 192.
        assertThat(
            plan,
        ).isEqualTo(PlaybackPlan(frameBytes = 2, capacityFrames = 8_192, startFrames = 2_048, initialFrames = 3_072))
        val rounded =
            PlaybackPlan.of(
                media = false,
                sampleRate = 48_000,
                channels = 1,
                minBufferBytes = 3_840,
                burstFrames = 500,
            )
        assertThat(rounded.initialFrames).isEqualTo(3_500)
    }

    @Test
    fun `a smaller track than asked for keeps the start one write below its working size`() {
        val plan = PlaybackPlan(frameBytes = 4, capacityFrames = 24_000, startFrames = 14_400, initialFrames = 24_000)

        assertThat(plan.fitted(10_000)).isEqualTo(PlaybackPlan(4, 10_000, 10_000 - 512, 10_000))
        assertThat(plan.fitted(0)).isEqualTo(plan)
        // Never below one write.
        assertThat(plan.fitted(300).startFrames).isEqualTo(512)
    }

    @Test
    fun `the tuner grows one burst per underrun while sound waited, up to the capacity, and never for a gap`() {
        val tuner = LatencyTuner(burstFrames = 192, capacityFrames = 3_500, initialFrames = 3_072, initialUnderruns = 2)

        assertThat(tuner.observe(underrunCount = 2, soundWaiting = true)).isNull()
        assertThat(tuner.observe(underrunCount = 3, soundWaiting = false)).isNull()
        assertThat(tuner.observe(underrunCount = 3, soundWaiting = true)).isNull()
        assertThat(tuner.observe(underrunCount = 4, soundWaiting = true)).isEqualTo(3_264)
        assertThat(tuner.observe(underrunCount = 5, soundWaiting = true)).isEqualTo(3_456)
        assertThat(tuner.observe(underrunCount = 6, soundWaiting = true)).isEqualTo(3_500)
        assertThat(tuner.observe(underrunCount = 7, soundWaiting = true)).isNull()
        assertThat(tuner.frames).isEqualTo(3_500)
    }
}

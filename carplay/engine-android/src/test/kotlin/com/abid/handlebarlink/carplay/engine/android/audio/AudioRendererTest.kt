package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import android.media.AudioTrack
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.spi.AudioStreamInfo
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.carplay.protocol.audio.AudioFormat
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.testLogger
import org.junit.Test

/** One stream's playback (DiPlay AudioRenderer, AndroidMediaSink.kt:1829-2864) on a fake track and decoder. */
class AudioRendererTest {
    private val time = ManualTime()
    private val sink = RecordingLogSink()
    private val log = testLogger(sink, time)
    private val platform = FakeRendererPlatform()
    private val focusSystem = FakeFocusSystem()
    private val focus = AudioFocus(focusSystem, log)
    private val modes = FakeAudioModes()
    private val calls = CallAudio(modes, log)
    private val track get() = checkNotNull(platform.track)

    private val pcm48Mono = AudioFormat(AudioCodec.LPCM, 48_000, 1, 48_000)
    private val pcm48Stereo = AudioFormat(AudioCodec.LPCM, 48_000, 2, 48_000)

    private fun renderer(
        audioType: String,
        format: AudioFormat,
        type: Int = 100,
    ) = AudioRenderer(AudioStreamInfo(type, audioType, format, 0), platform, focus, calls, time, log)

    /** [count] bytes of the big-endian sample [value]. */
    private fun pcm(
        count: Int,
        value: Int,
    ) = ByteArray(count) { if (it % 2 == 0) (value ushr 8).toByte() else value.toByte() }

    private fun AudioRenderer.steps(count: Int) = repeat(count) { step(0) }

    @Test
    fun `directions play little-endian on the low-latency path once the start is queued, in 2 KiB writes`() {
        val renderer = renderer("default", pcm48Mono)
        check(renderer.prepare())
        assertThat(platform.requestedMode).isEqualTo(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        // Start 4096 bytes (2048 frames) plus one write: 3072 frames (PlaybackPlan).
        assertThat(track.calls).containsExactly("buffer 3072")

        renderer.packet(1, 0, pcm(4096, 0x1234))
        renderer.packet(2, 2048, pcm(1024, 0x5678))
        renderer.steps(2)

        assertThat(track.written.map { it.size }).containsExactly(2048, 2048, 1024)
        assertThat(track.calls).containsExactly("buffer 3072", "play")
        // The first sample fades in (1/480 of 0x1234 = 9); later samples are 0x1234 little-endian.
        assertThat(track.written[0].copyOf(2).toHex()).isEqualTo("0900")
        assertThat(track.written[1].copyOf(2).toHex()).isEqualTo("3412")
        assertThat(track.written[2].copyOf(4).toHex()).isEqualTo("78567856")
        assertThat(sink.events("carplay.audio.playing").size).isEqualTo(1)
    }

    @Test
    fun `reordered packets play in sequence order and late duplicates never play`() {
        val renderer = renderer("default", pcm48Mono)
        check(renderer.prepare())
        renderer.packet(1, 0, pcm(1024, 0x0101))
        renderer.packet(3, 1024, pcm(1024, 0x0303))
        renderer.packet(2, 512, pcm(1024, 0x0202))
        renderer.packet(2, 512, pcm(1024, 0x0202))
        renderer.steps(4)

        assertThat(track.written.map { it.last().toInt() }).containsExactly(1, 2, 3)
    }

    @Test
    fun `Opus goes to the decoder, too short packets are skipped, and the PCM it makes is played`() {
        val renderer = renderer("speechrecognition", AudioFormat(AudioCodec.OPUS, 48_000, 1, 24_000))
        check(renderer.prepare())
        renderer.packet(1, 0, byteArrayOf(1, 2, 3))
        renderer.packet(2, 24_000, byteArrayOf(0xF8.toByte(), 1, 2, 3))
        renderer.steps(2)

        // The presentation time counts at the decode rate, as DiPlay's sampleTimestampUs (AndroidMediaSink.kt:2420).
        assertThat(platform.decoder.queued).containsExactly("f8010203" to 500_000L)
        assertThat(track.bytes.size).isEqualTo(4)
    }

    @Test
    fun `AAC-LC access units go to the decoder as ADTS frames`() {
        val renderer = renderer("media", AudioFormat(AudioCodec.AAC_LC, 48_000, 2, 48_000), type = 102)
        check(renderer.prepare())
        renderer.packet(1, 1024, byteArrayOf(0x21, 0x10))
        renderer.packet(2, 2048, ByteArray(0))
        renderer.steps(2)

        assertThat(platform.decoder.queued.map { it.first }).containsExactly("fff14c80013ffc" + "2110")
    }

    @Test
    fun `a decoder that takes no input is drained between tries and the packet dropped after ten`() {
        val renderer = renderer("speechrecognition", AudioFormat(AudioCodec.OPUS, 48_000, 1, 48_000))
        check(renderer.prepare())
        platform.decoder.busy = 10
        renderer.packet(1, 0, ByteArray(8))
        renderer.step(0)
        assertThat(platform.decoder.queued).isEmpty()
        assertThat(platform.decoder.drains).isGreaterThanOrEqualTo(10)

        platform.decoder.busy = 3
        renderer.packet(2, 960, ByteArray(8))
        renderer.step(0)
        assertThat(platform.decoder.queued.size).isEqualTo(1)
        renderer.release()
        assertThat(platform.decoder.released).isTrue()
    }

    @Test
    fun `a short burst plays half a second after its last write`() {
        val renderer = renderer("alert", pcm48Mono)
        check(renderer.prepare())
        renderer.packet(1, 0, pcm(1024, 0x0101))
        renderer.step(0)
        time.now += 499
        renderer.step(0)
        assertThat(track.playing).isFalse()

        time.now += 1
        renderer.step(0)

        assertThat(track.playing).isTrue()
    }

    @Test
    fun `music that runs dry pauses and queues its 300 ms again`() {
        val renderer = renderer("media", pcm48Stereo)
        check(renderer.prepare())
        assertThat(platform.requestedMode).isEqualTo(AudioTrack.PERFORMANCE_MODE_NONE)
        // 300 ms of 48 kHz stereo is 57 600 bytes.
        repeat(15) { renderer.packet(it, it * 1024L, pcm(4096, 0x0102)) }
        renderer.steps(15)
        assertThat(track.playing).isTrue()

        track.underrunCount = 1
        track.playAll()
        renderer.step(0)

        assertThat(track.calls.takeLast(2)).containsExactly("play", "pause")
        assertThat(sink.events("carplay.audio.rebuffer").size).isEqualTo(1)
        repeat(14) { renderer.packet(20 + it, (20 + it) * 1024L, pcm(4096, 0x0102)) }
        renderer.steps(14)
        assertThat(track.playing).isFalse()
        renderer.packet(40, 40 * 1024L, pcm(4096, 0x0102))
        renderer.step(0)
        assertThat(track.playing).isTrue()
    }

    @Test
    fun `speech grows its working buffer by one burst when the device underruns with sound waiting`() {
        val renderer = renderer("default", pcm48Mono)
        check(renderer.prepare())
        renderer.packet(1, 0, pcm(4096, 0x0101))
        renderer.step(0)
        check(track.playing)

        renderer.packet(2, 2048, pcm(512, 0x0202))
        renderer.packet(3, 2304, pcm(512, 0x0303))
        track.underrunCount = 1
        renderer.step(0)

        assertThat(track.calls.last()).isEqualTo("buffer 3264")
        assertThat(
            sink
                .events("carplay.audio.buffer")
                .single()
                .fields
                .toMap()["bufferFrames"],
        ).isEqualTo(3264)
        // An underrun in a gap (nothing waiting) grows nothing.
        track.underrunCount = 2
        renderer.step(0)
        renderer.step(0)
        assertThat(sink.events("carplay.audio.buffer").size).isEqualTo(1)
    }

    @Test
    fun `music takes focus and follows a prompt duck, directions take none, and release gives it back`() {
        val music = renderer("media", pcm48Stereo, type = 102)
        check(music.prepare())
        focus.setPromptDuck(true)
        music.release()

        assertThat(focusSystem.requests).containsExactly(AudioManager.AUDIOFOCUS_GAIN to AudioChannel.MEDIA)
        assertThat(track.volumes).containsExactly(0.2f)
        assertThat(focusSystem.abandoned).containsExactly(AudioChannel.MEDIA)
        assertThat(track.released).isTrue()

        check(renderer("default", pcm48Mono).prepare())
        assertThat(focusSystem.requests.size).isEqualTo(1)
    }

    @Test
    fun `a call that plays on the earpiece moves to the loudspeaker`() {
        calls.enter(Any())
        val renderer = renderer("telephony", AudioFormat(AudioCodec.LPCM, 16_000, 1, 16_000))
        check(renderer.prepare())
        track.onEarpiece = true
        renderer.packet(1, 0, pcm(4096, 0x0101))
        renderer.step(0)

        assertThat(modes.speakers).isEqualTo(1)
    }

    @Test
    fun `a closed renderer ignores packets, its loop ends, and everything is released`() {
        val renderer = renderer("speechrecognition", AudioFormat(AudioCodec.OPUS, 48_000, 1, 48_000))
        var woken = 0
        renderer.onClose = { woken++ }
        renderer.close()
        renderer.close()
        renderer.packet(1, 0, ByteArray(8))
        renderer.run()

        assertThat(woken).isEqualTo(1)
        assertThat(platform.decoder.queued).isEmpty()
        assertThat(platform.decoder.released).isTrue()
        assertThat(track.released).isTrue()
        assertThat(
            sink
                .events("carplay.audio.stats")
                .single()
                .fields
                .toMap()["ended"],
        ).isEqualTo(true)
    }

    @Test
    fun `a decoder that cannot be built ends only this stream, logged, with nothing left open`() {
        platform.failDecoder = true
        val renderer = renderer("speechrecognition", AudioFormat(AudioCodec.OPUS, 48_000, 1, 48_000))

        renderer.run()

        assertThat(sink.events("carplay.audio.renderer_failed").size).isEqualTo(1)
        assertThat(platform.track == null).isTrue()
    }

    @Test
    fun `a format the device cannot play is refused before anything is built`() {
        val refusing = FakeRendererPlatform(minBuffer = -2)
        val renderer = AudioRenderer(AudioStreamInfo(100, "default", pcm48Mono, 0), refusing, focus, calls, time, log)

        assertThat(renderer.prepare()).isFalse()
        assertThat(refusing.track == null).isTrue()
        assertThat(sink.events("carplay.audio.track_unavailable").size).isEqualTo(1)
    }

    @Test
    fun `stats report the counts every five seconds`() {
        val renderer = renderer("default", pcm48Mono)
        check(renderer.prepare())
        renderer.packet(1, 0, pcm(64, 1))
        renderer.packet(1, 0, pcm(64, 1))
        renderer.step(0)
        time.now += 5_000
        renderer.step(0)

        val stats =
            sink
                .events("carplay.audio.stats")
                .single()
                .fields
                .toMap()
        assertThat(stats["rx"]).isEqualTo(2L)
        assertThat(stats["late"]).isEqualTo(1L)
        assertThat(stats["channel"]).isEqualTo(AudioChannel.NAVIGATION)
        val created =
            sink
                .events("carplay.audio.track")
                .single()
                .fields
                .toMap()
        assertThat(created["performance"]).isEqualTo("low_latency")
        assertThat(created["startMs"]).isEqualTo(42L)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

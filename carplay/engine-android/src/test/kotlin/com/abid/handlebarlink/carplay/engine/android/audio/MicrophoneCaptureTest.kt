package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneFormat
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.testLogger
import org.junit.Test

/** One stream's microphone (DiPlay MicrophoneUplink.kt:77-408) on a fake recorder and encoder. */
class MicrophoneCaptureTest {
    private val log = testLogger(RecordingLogSink(), ManualTime())
    private val modes = FakeAudioModes()
    private val calls = CallAudio(modes, log)
    private val bodies = mutableListOf<String>()

    private val call = MicrophoneFormat("telephony", AudioCodec.LPCM, 16_000, 1, 20, 0)
    private val siri = MicrophoneFormat("speechrecognition", AudioCodec.OPUS, 48_000, 1, 20, 64_000)

    /** Runs a capture of [format] over [reads]; the recorder stops it once they are used up. */
    private fun capture(
        format: MicrophoneFormat,
        vararg reads: Any,
        configure: FakeMicrophonePlatform.() -> Unit = {},
        starts: List<Boolean> = emptyList(),
    ): Pair<FakeMicrophonePlatform, FakeRecorder> {
        val recorder = FakeRecorder(ArrayDeque(reads.toList()), ArrayDeque(starts))
        val platform = FakeMicrophonePlatform(recorder).apply(configure)
        val capture = MicrophoneCapture(format, platform, calls, log) { bodies += it.toHex() }
        recorder.onEmpty = { capture.stop() }
        capture.run()
        return platform to recorder
    }

    @Test
    fun `a call records VOICE_COMMUNICATION in communication mode with its effects, in 20 ms frames`() {
        // 20 ms of 16 kHz mono is 640 bytes; reads of any size are cut into whole frames.
        val (platform, recorder) = capture(call, ByteArray(1_000) { 1 }, ByteArray(400) { 2 })

        assertThat(platform.sources).containsExactly(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        assertThat(bodies.map { it.length / 2 }).containsExactly(640, 640)
        assertThat(bodies[1].substring(0, 4) + bodies[1].substring(bodies[1].length - 4)).isEqualTo("01010202")
        // At least twice the minimum (1280) and four frames (2560).
        assertThat(platform.bufferBytes).isEqualTo(2_560)
        assertThat(modes.modes).containsExactly(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_NORMAL)
        assertThat(platform.effectsOpened to platform.effectsClosed).isEqualTo(1 to 1)
        assertThat(recorder.released).isTrue()
    }

    @Test
    fun `Siri records VOICE_RECOGNITION and sends what the Opus encoder makes of each 20 ms of 48 kHz`() {
        val (platform, _) = capture(siri, ByteArray(1_920), ByteArray(1_920))

        assertThat(platform.sources).containsExactly(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        assertThat(platform.encoderBitrate).isEqualTo(64_000)
        // 960 samples x 2 bytes per frame (MicrophonePacketizer.kt:25-41); the fake encoder answers f8 + size.
        assertThat(bodies).containsExactly("f880", "f880")
        assertThat(platform.encoderReleased).isTrue()
        assertThat(platform.effectsOpened).isEqualTo(0)
        assertThat(modes.modes).containsExactly()
    }

    @Test
    fun `a phone that refuses VOICE_RECOGNITION records Siri with VOICE_COMMUNICATION`() {
        val (platform, _) = capture(siri, configure = { refuse = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION) })

        assertThat(platform.sources).containsExactly(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
    }

    @Test
    fun `app audio records the plain microphone`() {
        val (platform, _) = capture(MicrophoneFormat("default", AudioCodec.LPCM, 48_000, 1, 10, 0))

        assertThat(platform.sources).containsExactly(MediaRecorder.AudioSource.MIC)
    }

    @Test
    fun `a read error restarts the recorder once, a second one ends capture and leaves the mode`() {
        val (_, recorder) = capture(call, AudioRecord.ERROR_INVALID_OPERATION, ByteArray(640))
        assertThat(recorder.starts).isEqualTo(2)
        assertThat(bodies.size).isEqualTo(1)

        assertFailure {
            capture(call, AudioRecord.ERROR_INVALID_OPERATION, AudioRecord.ERROR_INVALID_OPERATION)
        }.isInstanceOf<CarPlayException>()
        assertThat(modes.modes.last()).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun `a dead recorder is not restarted`() {
        var recorder: FakeRecorder? = null
        assertFailure {
            recorder = FakeRecorder(ArrayDeque(listOf<Any>(AudioRecord.ERROR_DEAD_OBJECT)))
            val platform = FakeMicrophonePlatform(checkNotNull(recorder))
            MicrophoneCapture(call, platform, calls, log) {}.run()
        }.isInstanceOf<CarPlayException>()
        assertThat(checkNotNull(recorder).starts).isEqualTo(1)
        assertThat(checkNotNull(recorder).released).isTrue()
    }

    @Test
    fun `a recorder that does not start is restarted once before capture fails`() {
        capture(call, ByteArray(640), starts = listOf(false, true))
        assertThat(bodies.size).isEqualTo(1)

        assertFailure { capture(call, starts = listOf(false, false)) }.isInstanceOf<CarPlayException>()
    }

    @Test
    fun `without RECORD_AUDIO nothing is built and the error names the permission`() {
        assertFailure { capture(call, configure = { permission = false }) }
            .isInstanceOf<CarPlayException>()
            .prop(CarPlayException::error)
            .isEqualTo(CarPlayError.PermissionMissing("android.permission.RECORD_AUDIO"))
        assertThat(modes.modes).containsExactly()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

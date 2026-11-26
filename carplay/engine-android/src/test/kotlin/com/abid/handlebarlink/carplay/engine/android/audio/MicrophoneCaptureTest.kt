package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadset
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadsetSource
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneFormat
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.testLogger
import org.junit.Test

/** One stream's microphone (DiPlay MicrophoneUplink.kt:77-408) on a fake recorder and encoder. */
class MicrophoneCaptureTest {
    private val log = testLogger(RecordingLogSink(), ManualTime())
    private val modes = FakeAudioModes()
    private val headsets = CarPlayHeadsetSource { CarPlayHeadset() }
    private val calls = CallAudio(modes, headsets, log, ManualTime())
    private val bodies = mutableListOf<String>()

    private val call = MicrophoneFormat("telephony", AudioCodec.LPCM, 16_000, 1, 20, 0)
    private val siri = MicrophoneFormat("speechrecognition", AudioCodec.OPUS, 48_000, 1, 20, 64_000)

    /** Runs a capture of [format] over [reads]; the recorder stops it once they are used up. */
    private fun capture(
        format: MicrophoneFormat,
        vararg reads: Any,
        configure: FakeMicrophonePlatform.() -> Unit = {},
        starts: List<Boolean> = emptyList(),
        muted: () -> Boolean = { false },
    ): Pair<FakeMicrophonePlatform, FakeRecorder> {
        val recorder = FakeRecorder(ArrayDeque(reads.toList()), ArrayDeque(starts))
        val platform = FakeMicrophonePlatform(recorder).apply(configure)
        val capture = MicrophoneCapture(format, platform, calls, log, muted) { bodies += it.toHex() }
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
        // Siri enters communication mode (and leaves it) like a call: the helmet's hands-free (SCO) link only opens in
        // that mode, so Siri was silent on the headset without it. The call-only echo canceller still stays off.
        assertThat(modes.modes).containsExactly(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_NORMAL)
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

    @Test
    fun `a call and Siri record from the connected intercom, which is given back when they end`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        val (callPlatform, _) = capture(call, ByteArray(640))
        val (siriPlatform, _) = capture(siri, ByteArray(1_920))

        assertThat(callPlatform.preferred).isEqualTo(INTERCOM)
        assertThat(siriPlatform.preferred).isEqualTo(INTERCOM)
        assertThat(modes.routed).containsExactly(INTERCOM, INTERCOM)
        assertThat(modes.cleared).isEqualTo(2)
        // Both the call and Siri enter communication mode and leave it: each needs the hands-free SCO link to the
        // intercom, which Android only opens in that mode.
        assertThat(modes.modes).containsExactly(
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_NORMAL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_NORMAL,
        )
    }

    @Test
    fun `an Opus encoder that breaks and then throws on release still gives back the mode and the intercom route`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        val opusCall = MicrophoneFormat("telephony", AudioCodec.OPUS, 48_000, 1, 20, 24_000)

        assertFailure {
            capture(opusCall, ByteArray(1_920), configure = {
                encode = { throw IllegalStateException("codec error") }
                encoderReleaseFails = true
            })
        }.isInstanceOf<IllegalStateException>()

        assertThat(modes.modes).containsExactly(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_NORMAL)
        assertThat(modes.cleared).isEqualTo(1)
        // A later call can enter the mode again: nothing still holds it.
        calls.enter(Any())
        assertThat(modes.modes.last()).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
    }

    @Test
    fun `app audio never takes the intercom route`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        val (platform, _) = capture(MicrophoneFormat("default", AudioCodec.LPCM, 48_000, 1, 10, 0), ByteArray(960))

        assertThat(platform.preferred).isNull()
        assertThat(modes.routed).isEmpty()
    }

    @Test
    fun `while Android's microphone mute is on, a call's uplink is silence of the same length`() {
        var muted = false
        val reads = ArrayDeque(listOf<() -> Unit>({ muted = true }, {}))
        val recorder = FakeRecorder(ArrayDeque(listOf<Any>(ByteArray(640) { 5 }, ByteArray(640) { 5 })))
        val platform = FakeMicrophonePlatform(recorder)
        val capture =
            MicrophoneCapture(call, platform, calls, log, { muted }) {
                bodies += it.toHex()
                reads.removeFirstOrNull()?.invoke()
            }
        recorder.onEmpty = { capture.stop() }
        capture.run()

        assertThat(bodies.size).isEqualTo(2)
        assertThat(bodies[0]).isEqualTo("05".repeat(640))
        assertThat(bodies[1]).isEqualTo("00".repeat(640))
    }

    @Test
    fun `while muted, Siri's Opus encoder is fed silence`() {
        val fed = mutableListOf<ByteArray>()
        capture(siri, ByteArray(1_920) { 9 }, configure = {
            encode = {
                fed += it
                listOf(byteArrayOf(0xF8.toByte()))
            }
        }, muted = { true })

        assertThat(fed.single().all { it == 0.toByte() }).isTrue()
        assertThat(fed.single().size).isEqualTo(1_920)
        assertThat(bodies).containsExactly("f8")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

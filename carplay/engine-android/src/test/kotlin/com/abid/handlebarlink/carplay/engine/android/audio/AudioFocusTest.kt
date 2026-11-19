package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.testLogger
import org.junit.Test

/** DiPlay's AudioFocusCoordinator (AndroidMediaSink.kt:41-193) with CarLink's prompt duck (#83). */
class AudioFocusTest {
    private val system = FakeFocusSystem()
    private val focus = AudioFocus(system, testLogger(RecordingLogSink(), ManualTime()))
    private val musicVolumes = mutableListOf<Float>()
    private val callVolumes = mutableListOf<Float>()
    private val music = AudioRoute.of(102, "media")
    private val call = AudioRoute.of(100, "telephony")
    private val siri = AudioRoute.of(100, "speechrecognition")

    @Test
    fun `one request follows the highest playing route and is abandoned with the last track`() {
        focus.acquire("siri", siri) {}
        focus.acquire("call", call) {}
        focus.acquire("music", music) {}
        focus.release("music")
        focus.release("call")
        focus.release("siri")

        assertThat(system.requests).containsExactly(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to AudioChannel.ASSISTANT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT to AudioChannel.PHONE,
            AudioManager.AUDIOFOCUS_GAIN to AudioChannel.MEDIA,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT to AudioChannel.PHONE,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to AudioChannel.ASSISTANT,
        )
        assertThat(system.abandoned).containsExactly(
            AudioChannel.ASSISTANT,
            AudioChannel.PHONE,
            AudioChannel.MEDIA,
            AudioChannel.PHONE,
            AudioChannel.ASSISTANT,
        )
    }

    @Test
    fun `directions take no focus`() {
        focus.acquire("directions", AudioRoute.of(100, "default")) {}

        assertThat(system.requests).isEmpty()
    }

    @Test
    fun `a duckable loss lowers music, a transient one mutes it, a gain restores it, a permanent one does nothing`() {
        focus.acquire("music", music) { musicVolumes += it }
        focus.acquire("call", call) { callVolumes += it }
        val change = system.changes

        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        change(AudioManager.AUDIOFOCUS_GAIN)
        change(AudioManager.AUDIOFOCUS_LOSS)

        assertThat(musicVolumes).containsExactly(0.2f, 0f, 1f)
        assertThat(callVolumes).isEmpty()
    }

    @Test
    fun `a prompt ducks music to the quieter of the two reasons and unduck gives the focus level back`() {
        focus.acquire("music", music) { musicVolumes += it }
        system.changes(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        focus.setPromptDuck(true)
        focus.setPromptDuck(true)
        system.changes(AudioManager.AUDIOFOCUS_GAIN)
        focus.setPromptDuck(false)

        assertThat(musicVolumes).containsExactly(0f, 0.2f, 1f)
    }

    @Test
    fun `music that starts during a prompt starts ducked`() {
        focus.setPromptDuck(true)
        focus.acquire("music", music) { musicVolumes += it }

        assertThat(musicVolumes).containsExactly(0.2f)
    }

    @Test
    fun `a change for a replaced request is ignored`() {
        focus.acquire("music", music) { musicVolumes += it }
        val stale = system.changes
        focus.release("music")
        focus.acquire("music", music) { musicVolumes += it }

        stale(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertThat(musicVolumes).isEmpty()
    }

    @Test
    fun `after close nothing is requested`() {
        focus.close()
        focus.acquire("music", music) {}
        focus.setPromptDuck(true)

        assertThat(system.requests).isEmpty()
    }
}

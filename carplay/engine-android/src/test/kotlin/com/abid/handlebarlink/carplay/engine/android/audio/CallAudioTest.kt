package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.testLogger
import org.junit.Test

/** DiPlay's call audio mode and loudspeaker fallback (AndroidMediaSink.kt:581-678, CallSpeaker.kt). */
class CallAudioTest {
    private val modes = FakeAudioModes()
    private val calls = CallAudio(modes, testLogger(RecordingLogSink(), ManualTime()))

    @Test
    fun `a call enters communication mode once and only its owner leaves it with MODE_NORMAL`() {
        val call = Any()
        calls.enter(call)
        calls.enter(Any())
        calls.leave(Any())
        calls.leave(call)

        assertThat(modes.modes).containsExactly(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_NORMAL)
    }

    @Test
    fun `an earpiece route moves the call to the loudspeaker once, and leaving clears it`() {
        val call = Any()
        calls.routedToEarpiece()
        calls.enter(call)
        calls.routedToEarpiece()
        calls.routedToEarpiece()
        calls.leave(call)

        assertThat(modes.speakers).isEqualTo(1)
        assertThat(modes.cleared).isEqualTo(1)
    }

    @Test
    fun `a refused loudspeaker is tried once per call and never cleared`() {
        modes.speakerGranted = false
        val first = Any()
        calls.enter(first)
        calls.routedToEarpiece()
        calls.routedToEarpiece()
        calls.leave(first)
        calls.enter(Any())
        calls.routedToEarpiece()

        assertThat(modes.speakers).isEqualTo(2)
        assertThat(modes.cleared).isEqualTo(0)
    }
}

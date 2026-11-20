package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioManager
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadset
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadsetSource
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.testLogger
import org.junit.Test

/**
 * The route of CarPlay's calls and Siri: the rider's intercom when it is connected, else DiPlay's call audio mode and
 * loudspeaker fallback (AndroidMediaSink.kt:581-678, CallSpeaker.kt).
 */
class CallAudioTest {
    private val modes = FakeAudioModes()
    private var preferred: CarPlayHeadset? = CarPlayHeadset()
    private val time = ManualTime()
    private val calls = CallAudio(modes, CarPlayHeadsetSource { preferred }, testLogger(RecordingLogSink(), time), time)

    @Test
    fun `a call enters communication mode once and only a microphone that entered leaves it with MODE_NORMAL`() {
        val call = Any()
        calls.enter(call)
        calls.enter(call)
        calls.leave(Any())
        calls.leave(call)

        assertThat(modes.modes).containsExactly(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_NORMAL)
    }

    @Test
    fun `a replaced call microphone that leaves after the new one entered keeps the new call in communication mode`() {
        val old = Any()
        val new = Any()
        calls.enter(old)
        calls.enter(new)

        calls.leave(old)
        val afterOld = modes.modes.toList()
        calls.leave(new)

        assertThat(afterOld).containsExactly(AudioManager.MODE_IN_COMMUNICATION)
        assertThat(modes.modes).containsExactly(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_NORMAL)
    }

    @Test
    fun `without a headset an earpiece route moves the call to the loudspeaker once, and leaving clears it`() {
        val call = Any()
        calls.routedToEarpiece()
        calls.enter(call)
        assertThat(calls.voiceStarted(call, AudioChannel.PHONE)).isNull()
        calls.routedToEarpiece()
        calls.routedToEarpiece()
        calls.voiceEnded(call)
        calls.leave(call)

        assertThat(modes.speakers).isEqualTo(1)
        assertThat(modes.cleared).isEqualTo(1)
        assertThat(modes.routed).isEmpty()
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

    @Test
    fun `with the intercom connected a call's sound and microphone go to it, never to the loudspeaker`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        val microphone = Any()
        val track = Any()
        calls.enter(microphone)
        val forMicrophone = calls.voiceStarted(microphone, AudioChannel.PHONE)
        val forTrack = calls.voiceStarted(track, AudioChannel.PHONE)
        // A passing earpiece report while the intercom is still connected changes nothing.
        calls.routedToEarpiece()
        calls.voiceEnded(microphone)
        calls.leave(microphone)
        assertThat(modes.cleared).isEqualTo(0)
        calls.voiceEnded(track)

        assertThat(forMicrophone).isEqualTo(INTERCOM)
        assertThat(forTrack).isEqualTo(INTERCOM)
        assertThat(modes.routed).containsExactly(INTERCOM)
        assertThat(modes.speakers).isEqualTo(0)
        assertThat(modes.cleared).isEqualTo(1)
    }

    @Test
    fun `Siri goes to the intercom without changing the audio mode`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        val siri = Any()
        assertThat(calls.voiceStarted(siri, AudioChannel.ASSISTANT)).isEqualTo(INTERCOM)
        calls.voiceEnded(siri)

        assertThat(modes.routed).containsExactly(INTERCOM)
        assertThat(modes.cleared).isEqualTo(1)
        assertThat(modes.modes).isEmpty()
    }

    @Test
    fun `music and directions never pick a device, A2DP carries them`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        val music = Any()
        val directions = Any()
        assertThat(calls.voiceStarted(music, AudioChannel.MEDIA)).isNull()
        assertThat(calls.voiceStarted(directions, AudioChannel.NAVIGATION)).isNull()
        calls.voiceEnded(music)
        calls.voiceEnded(directions)

        assertThat(modes.routed).isEmpty()
        assertThat(modes.cleared).isEqualTo(0)
    }

    @Test
    fun `a rider who sends calls to the phone keeps the phone's route and the loudspeaker fallback`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        preferred = null
        val call = Any()
        calls.enter(call)
        assertThat(calls.voiceStarted(call, AudioChannel.PHONE)).isNull()
        calls.routedToEarpiece()

        assertThat(modes.routed).isEmpty()
        assertThat(modes.speakers).isEqualTo(1)
    }

    @Test
    fun `an intercom that disconnects mid-call falls back to the loudspeaker`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        val call = Any()
        calls.enter(call)
        calls.voiceStarted(call, AudioChannel.PHONE)
        modes.devices = listOf(EARPIECE, SPEAKER)
        calls.routedToEarpiece()
        calls.voiceEnded(call)
        calls.leave(call)

        assertThat(modes.speakers).isEqualTo(1)
        // The loudspeaker is cleared when the call leaves; the lost intercom's route is gone already.
        assertThat(modes.cleared).isEqualTo(1)
    }

    @Test
    fun `an intercom whose link Android drops while it stays listed sends the call to the loudspeaker`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        val call = Any()
        calls.enter(call)
        check(calls.voiceStarted(call, AudioChannel.PHONE) == INTERCOM)

        modes.moveTo(EARPIECE) // The intercom refused the SCO link mid-call; it is still connected and listed.

        assertThat(modes.speakers).isEqualTo(1)
        assertThat(modes.cleared).isEqualTo(1)
        // The next track asks again: the failed headset is not taken for granted.
        assertThat(calls.voiceStarted(Any(), AudioChannel.PHONE)).isEqualTo(INTERCOM)
    }

    @Test
    fun `an accepted request that Android never applies is a passing earpiece report at first, a failed route later`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        modes.appliesRoutes = false
        val call = Any()
        calls.enter(call)
        check(calls.voiceStarted(call, AudioChannel.PHONE) == INTERCOM)

        calls.routedToEarpiece()
        val early = modes.speakers
        time.now = CallAudio.CONFIRM_MILLIS
        calls.routedToEarpiece()

        assertThat(early).isEqualTo(0)
        assertThat(modes.speakers).isEqualTo(1)
    }

    @Test
    fun `an applied intercom route is confirmed and its earpiece reports stay passing ones`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        modes.appliesRoutes = false
        val call = Any()
        calls.enter(call)
        calls.voiceStarted(call, AudioChannel.PHONE)
        modes.moveTo(INTERCOM)

        time.now = 10 * CallAudio.CONFIRM_MILLIS
        calls.routedToEarpiece()

        assertThat(modes.speakers).isEqualTo(0)
    }

    @Test
    fun `a refused intercom route keeps the phone's fallback`() {
        modes.devices = listOf(EARPIECE, SPEAKER, INTERCOM)
        modes.deviceGranted = false
        val call = Any()
        calls.enter(call)
        assertThat(calls.voiceStarted(call, AudioChannel.PHONE)).isNull()
        calls.routedToEarpiece()
        calls.voiceEnded(call)

        assertThat(modes.speakers).isEqualTo(1)
        assertThat(modes.cleared).isEqualTo(0)
    }
}

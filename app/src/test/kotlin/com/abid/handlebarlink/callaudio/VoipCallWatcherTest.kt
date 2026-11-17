package com.abid.handlebarlink.callaudio

import android.media.AudioAttributes
import android.media.MediaRecorder
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class VoipCallWatcherTest {
    private val callMic = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private val callSound = AudioAttributes.USAGE_VOICE_COMMUNICATION

    @Test
    fun `a call needs the call microphone and call sound together (CarLink's CarPlay call)`() {
        assertThat(isCall(listOf(callMic), listOf(callSound, AudioAttributes.USAGE_MEDIA))).isTrue()
    }

    @Test
    fun `music, Siri and voice notes are not calls`() {
        assertThat(isCall(emptyList(), listOf(AudioAttributes.USAGE_MEDIA))).isFalse()
        assertThat(
            isCall(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION), listOf(AudioAttributes.USAGE_ASSISTANT)),
        ).isFalse()
        assertThat(isCall(listOf(MediaRecorder.AudioSource.MIC), emptyList())).isFalse()
        assertThat(isCall(listOf(callMic), emptyList())).isFalse()
        assertThat(isCall(emptyList(), listOf(callSound))).isFalse()
    }
}

package com.abid.handlebarlink.carplay.engine.android.audio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadset
import org.junit.Test

/** Which device carries CarPlay's calls and Siri (docs/PLAN.md "C5 audio", "Routes"). */
class VoiceRouteTest {
    private val phone = listOf(EARPIECE, SPEAKER)
    private val both = phone + OTHER_HEADSET + INTERCOM

    @Test
    fun `the rider's intercom is found by address, in any case`() {
        assertThat(chooseHeadset(both, CarPlayHeadset("aa:bb:cc:dd:ee:01", "renamed"))).isEqualTo(INTERCOM)
    }

    @Test
    fun `then by its Bluetooth name, then any headset`() {
        assertThat(chooseHeadset(both, CarPlayHeadset("11:22:33:44:55:66", "EJEAS V6 Pro+"))).isEqualTo(INTERCOM)
        assertThat(chooseHeadset(both, CarPlayHeadset("11:22:33:44:55:66", "gone"))).isEqualTo(OTHER_HEADSET)
        assertThat(chooseHeadset(both, CarPlayHeadset())).isEqualTo(OTHER_HEADSET)
    }

    @Test
    fun `no headset connected or calls on the phone leave the route to Android`() {
        assertThat(chooseHeadset(phone, CarPlayHeadset())).isNull()
        assertThat(chooseHeadset(both, null)).isNull()
    }

    @Test
    fun `only calls and Siri take the headset route`() {
        assertThat(AudioChannel.PHONE.voiceUsesHeadset).isTrue()
        assertThat(AudioChannel.ASSISTANT.voiceUsesHeadset).isTrue()
        assertThat(AudioChannel.MEDIA.voiceUsesHeadset).isFalse()
        assertThat(AudioChannel.NAVIGATION.voiceUsesHeadset).isFalse()
    }
}

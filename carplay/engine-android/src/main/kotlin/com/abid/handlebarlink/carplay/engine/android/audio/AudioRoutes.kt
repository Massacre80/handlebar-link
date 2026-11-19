package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack

/** What a CarPlay stream is to Android (DiPlay AudioChannelMapping.kt:10-15). */
internal enum class AudioChannel {
    /** Music and podcasts. */
    MEDIA,

    /** A call. */
    PHONE,

    /** Siri. */
    ASSISTANT,

    /** Directions, alerts and app sounds. */
    NAVIGATION,
}

/** How one stream plays: its channel and the attributes its track and focus request carry. */
internal data class AudioRoute(
    val channel: AudioChannel,
    val usage: Int,
    val contentType: Int,
) {
    /** Media keeps the jitter buffer that outlasts Wi-Fi gaps (DiPlay MediaAudioBuffer.kt:3-8); the rest is speech. */
    val isMedia: Boolean get() = channel == AudioChannel.MEDIA

    /**
     * The focus gain a track of this route asks for, or null for none (DiPlay AudioFocusCoordinator,
     * AndroidMediaSink.kt:146-151): navigation overlays the rest without focus (AndroidMediaSink.kt:2275-2287).
     */
    val focusGain: Int?
        get() =
            when (channel) {
                AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
                AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                AudioChannel.NAVIGATION -> null
            }

    /** The one focus request follows the highest of the playing routes (AndroidMediaSink.kt:177-182). */
    val focusPriority: Int
        get() =
            when (channel) {
                AudioChannel.MEDIA -> PRIORITY_MEDIA
                AudioChannel.PHONE -> PRIORITY_PHONE
                AudioChannel.ASSISTANT -> PRIORITY_ASSISTANT
                AudioChannel.NAVIGATION -> PRIORITY_NAVIGATION
            }

    /**
     * Speech plays on Android's low-latency path when it can: the track's rate must be the output's own
     * ([nativeRate], AudioTrack.Builder#setPerformanceMode). Music keeps the normal path: its 300 ms jitter buffer
     * dwarfs the mixer's latency and the normal path costs less power on a ride of hours.
     */
    fun performanceMode(
        sampleRate: Int,
        nativeRate: Int,
    ): Int =
        if (!isMedia && sampleRate == nativeRate) {
            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        } else {
            AudioTrack.PERFORMANCE_MODE_NONE
        }

    companion object {
        private const val PRIORITY_MEDIA = 3
        private const val PRIORITY_PHONE = 2
        private const val PRIORITY_ASSISTANT = 1
        private const val PRIORITY_NAVIGATION = 0
        private const val MAIN_HIGH_AUDIO = 102

        /**
         * DiPlay's phone mapping (AudioChannelMapper MOBILE_COMPATIBLE, AudioChannelMapping.kt:67-100; usages and
         * content types AndroidMediaSink.kt:2322-2337): telephony is a call, speechrecognition Siri, media music,
         * default/alert/compatibility directions; any other audio type is music on stream 102, else directions.
         */
        fun of(
            type: Int,
            audioType: String,
        ): AudioRoute {
            val channel =
                when (audioType.lowercase()) {
                    "telephony" -> AudioChannel.PHONE
                    "speechrecognition" -> AudioChannel.ASSISTANT
                    "media" -> AudioChannel.MEDIA
                    "default", "alert", "compatibility" -> AudioChannel.NAVIGATION
                    else -> if (type == MAIN_HIGH_AUDIO) AudioChannel.MEDIA else AudioChannel.NAVIGATION
                }
            return AudioRoute(channel, usage(channel), contentType(channel))
        }

        private fun usage(channel: AudioChannel): Int =
            when (channel) {
                AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
                AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
                AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            }

        private fun contentType(channel: AudioChannel): Int =
            if (channel ==
                AudioChannel.MEDIA
            ) {
                AudioAttributes.CONTENT_TYPE_MUSIC
            } else {
                AudioAttributes.CONTENT_TYPE_SPEECH
            }
    }
}

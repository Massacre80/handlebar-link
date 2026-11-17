package com.abid.handlebarlink.devices

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks a short line in the helmet with Android's text to speech, as a navigation prompt (music ducks
 * under it and it plays where media plays, the intercom). The engine starts on first use and stays.
 */
@Singleton
class BatteryAnnouncer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) {
        private val log = logger.child("announce")
        private var speech: TextToSpeech? = null
        private var ready = false
        private var waiting: String? = null

        /** Says [text] now, or as soon as the engine is ready. */
        fun say(text: String) {
            val engine = speech
            when {
                engine == null -> {
                    waiting = text
                    speech =
                        TextToSpeech(context) { status ->
                            ready = status == TextToSpeech.SUCCESS
                            log.info("announce.ready", "ok" to ready)
                            speech?.setAudioAttributes(PROMPT)
                            waiting?.let { if (ready) speak(it) }
                            waiting = null
                        }
                }

                ready -> {
                    speak(text)
                }

                else -> {
                    waiting = text
                }
            }
        }

        private fun speak(text: String) {
            speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
        }

        private companion object {
            const val UTTERANCE = "battery"
            val PROMPT: AudioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
        }
    }

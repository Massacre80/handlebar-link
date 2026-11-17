package com.abid.handlebarlink.callaudio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * Whether an app on the phone is in a voice call, from public audio state: something records from
 * VOICE_COMMUNICATION and something plays USAGE_VOICE_COMMUNICATION. CarLink plays a CarPlay call exactly
 * like that (docs/research/04-intercom-audio.md §1.3); DiPlay detects other apps' calls the same way
 * (`VoipCallWatcher`, its "car phone audio for VoIP calls" option).
 */
class VoipCallWatcher
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) {
        private val log = logger.child("callaudio")

        /** True while a voice call runs; the callbacks are registered while collected. */
        fun inCall(): Flow<Boolean> =
            callbackFlow {
                val audio = context.getSystemService(AudioManager::class.java)
                if (audio == null) {
                    trySend(false)
                    awaitClose {}
                    return@callbackFlow
                }
                var recording = audio.activeRecordingConfigurations.map { it.clientAudioSource }
                var playing = audio.activePlaybackConfigurations.mapNotNull { it.audioAttributes?.usage }

                fun publish() {
                    val call = isCall(recording, playing)
                    log.debug("callaudio.audio", "recording" to recording, "playing" to playing, "call" to call)
                    trySend(call)
                }
                val recordings =
                    object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                            recording = configs.map { it.clientAudioSource }
                            publish()
                        }
                    }
                val playbacks =
                    object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                            playing = configs.mapNotNull { it.audioAttributes?.usage }
                            publish()
                        }
                    }
                val main = Handler(Looper.getMainLooper())
                audio.registerAudioRecordingCallback(recordings, main)
                audio.registerAudioPlaybackCallback(playbacks, main)
                publish()
                awaitClose {
                    audio.unregisterAudioRecordingCallback(recordings)
                    audio.unregisterAudioPlaybackCallback(playbacks)
                }
            }.distinctUntilChanged()
    }

/** A voice call: a VOICE_COMMUNICATION recording and a USAGE_VOICE_COMMUNICATION playback at once. */
internal fun isCall(
    recordingSources: List<Int>,
    playbackUsages: List<Int>,
): Boolean =
    MediaRecorder.AudioSource.VOICE_COMMUNICATION in recordingSources &&
        AudioAttributes.USAGE_VOICE_COMMUNICATION in playbackUsages

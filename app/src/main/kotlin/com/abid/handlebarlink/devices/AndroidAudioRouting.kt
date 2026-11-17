package com.abid.handlebarlink.devices

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * [AudioRouting] from public APIs only (the research's rules: no hidden APIs): Android 13's
 * `AudioManager.getAudioDevicesForAttributes` for where media plays, re-read when audio devices come and go;
 * Android 13's `MediaSessionManager` media-key session (allowed for apps with an enabled notification
 * listener, which this app has for the handlebar controls);.
 * Android 12 gets [MediaRoute.Unknown] and [MediaKeys.Unknown]: one version branch per feature, here.
 */
class AndroidAudioRouting
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : AudioRouting {
        private val log = logger.child("routing")
        private val audio: AudioManager? get() = context.getSystemService(AudioManager::class.java)

        override fun mediaRoute(): Flow<MediaRoute> {
            val audio = audio
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || audio == null) return flowOf(MediaRoute.Unknown)
            return callbackFlow {
                fun now(): MediaRoute =
                    try {
                        val device = audio.getAudioDevicesForAttributes(MEDIA).firstOrNull()
                        if (device != null && device.type in BLUETOOTH) {
                            MediaRoute.Bluetooth(device.address)
                        } else {
                            MediaRoute.Phone
                        }
                    } catch (e: SecurityException) {
                        log.warn("routing.media.denied", "error" to e.message)
                        MediaRoute.Unknown
                    }
                val callback =
                    object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                            trySend(now())
                        }

                        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                            trySend(now())
                        }
                    }
                // Registering reports the current devices once, which sends the first value.
                audio.registerAudioDeviceCallback(callback, null)
                trySend(now())
                awaitClose { audio.unregisterAudioDeviceCallback(callback) }
            }.distinctUntilChanged()
        }

        override fun mediaKeys(): Flow<MediaKeys> {
            val sessions = context.getSystemService(MediaSessionManager::class.java)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                sessions == null
            ) {
                return flowOf(MediaKeys.Unknown)
            }
            return callbackFlow {
                fun now(): MediaKeys =
                    try {
                        sessions.mediaKeyEventSessionPackageName.takeIf { it.isNotEmpty() }?.let(MediaKeys::App)
                            ?: MediaKeys.Unknown
                    } catch (e: SecurityException) {
                        // Notification access was turned off.
                        log.warn("routing.keys.denied", "error" to e.message)
                        MediaKeys.Unknown
                    }
                val listener = MediaSessionManager.OnMediaKeyEventSessionChangedListener { _, _ -> trySend(now()) }
                try {
                    sessions.addOnMediaKeyEventSessionChangedListener(ContextCompat.getMainExecutor(context), listener)
                } catch (e: SecurityException) {
                    log.warn("routing.keys.listen.denied", "error" to e.message)
                }
                trySend(now())
                awaitClose { sessions.removeOnMediaKeyEventSessionChangedListener(listener) }
            }.distinctUntilChanged()
        }

        // Android 14+ only (Android 12–13 needed a system permission to choose): checked at each call.
        override fun mediaOutputs(): Flow<List<MediaOutput>> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                MediaOutputs(context, log).outputs()
            } else {
                flowOf(emptyList())
            }

        override fun playOn(id: String): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && MediaOutputs(context, log).playOn(id)

        override fun vendorApp(): Intent? =
            VENDOR_APPS.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }

        private companion object {
            val MEDIA: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            val BLUETOOTH =
                setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                )

            /** Intercom makers' apps (declared in the manifest's <queries>): EJEAS's official app. */
            val VENDOR_APPS = listOf("com.ejeas.ejeasapp")
        }
    }

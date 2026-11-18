package com.abid.handlebarlink.controls

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.view.KeyEvent
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Sends actions to CarLink's CarPlay media session only, never as a broadcast, so no other app on the
 * phone receives them (the prototype's verified path; interim until our own CarPlay engine). Only the music
 * keys: CarLink takes call keys only from a car's key events or a broadcast that needs the system DUMP
 * permission (CarLink `CarPlayCallKeys.kt:52`), never through its media session (docs/LESSONS.md 44), so
 * answer, decline, end and Siri come back [Unavailable.NEEDS_OUR_CARPLAY].
 */
class MediaSessionControlTarget
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val log: Logger,
    ) : ControlTarget {
        private val sessions = context.getSystemService(MediaSessionManager::class.java)
        private val listener = ComponentName(context, MediaAccessListener::class.java)

        override val supported: Set<ControlAction> = MEDIA_KEYS.keys

        override fun perform(action: ControlAction): ControlResult {
            val code = MEDIA_KEYS[action] ?: return ControlResult.NotDone(Unavailable.NEEDS_OUR_CARPLAY)
            val active =
                try {
                    sessions.getActiveSessions(listener)
                } catch (e: SecurityException) {
                    log.warn("control.no_media_access", "error" to e.message)
                    null
                }
            val session = active?.firstOrNull { it.packageName in CARLINK_PACKAGES }
            return when {
                active == null -> {
                    ControlResult.NotDone(Unavailable.NO_MEDIA_ACCESS)
                }

                session == null -> {
                    ControlResult.NotDone(Unavailable.NO_CARPLAY_SESSION)
                }

                else -> {
                    press(session, code)
                    ControlResult.Done
                }
            }
        }

        private fun press(
            session: MediaController,
            code: Int,
        ) {
            val now = SystemClock.uptimeMillis()
            session.dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            session.dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
    }

/** The CarPlay receiver's packages: the official CarLink and its debug build. */
val CARLINK_PACKAGES: Set<String> = setOf("com.carlink.app", "com.carlink.app.hudtest")

/** The actions CarLink's media session takes, and the media key each sends. */
private val MEDIA_KEYS: Map<ControlAction, Int> =
    mapOf(
        ControlAction.PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        ControlAction.NEXT to KeyEvent.KEYCODE_MEDIA_NEXT,
        ControlAction.PREVIOUS to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

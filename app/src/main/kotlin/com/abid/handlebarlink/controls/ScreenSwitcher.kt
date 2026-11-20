package com.abid.handlebarlink.controls

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.abid.handlebarlink.MainActivity
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.ride.EXTRA_RIDE
import com.abid.handlebarlink.ride.RideStarter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** A page a handlebar press asks the app to show ([ScreenSwitcher]); the app's navigation acts on it. */
enum class ScreenRequest {
    /** Our CarPlay display (the CarPlay tab's full screen). */
    OPEN_CARPLAY,

    /** Leaves the CarPlay display when it is shown, else opens it. */
    SWITCH,
}

/** The [MainActivity] extra that carries a [ScreenRequest] by name. */
const val EXTRA_SCREEN: String = "screen"

/** The [ScreenRequest] an intent's [EXTRA_SCREEN] names, or null (any other value is ignored). */
fun screenRequest(name: String?): ScreenRequest? = ScreenRequest.entries.firstOrNull { it.name == name }

/**
 * What the phone shows from a handlebar press: our CarPlay display or this app's other pages. Both are this app since
 * our CarPlay replaced CarLink (owner, 2026-11-20): the request goes to [MainActivity] ([EXTRA_SCREEN]), which turns
 * the screen on and shows over the lock screen as at ignition ([EXTRA_RIDE]) and opens the page. Android lets the app
 * start its activity from the background because it is the bike's companion app and, once the rider allows it,
 * because it may display over other apps (developer.android.com, "Activity security", exceptions: SYSTEM_ALERT_WINDOW).
 * A refused start only shows in the system log ("Background activity launch blocked").
 */
class ScreenSwitcher
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val rides: RideStarter,
        logger: Logger,
    ) {
        private val log = logger.child("control")

        /** Our CarPlay display, over whatever is in front. */
        fun openCarPlay(): Boolean = show(ScreenRequest.OPEN_CARPLAY)

        /** This app's screen (as at ignition: over the lock screen). */
        fun openApp(): Boolean = rides.showOnScreen().also { log.info("screen.app", "asked" to it) }

        /** With this app in front, the CarPlay display or back from it; else this app. */
        fun switch(): Boolean = if (appInFront()) show(ScreenRequest.SWITCH) else openApp()

        private fun show(request: ScreenRequest): Boolean =
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(EXTRA_RIDE, true)
                        .putExtra(EXTRA_SCREEN, request.name),
                )
                log.info("screen.carplay", "request" to request.name)
                true
            } catch (e: ActivityNotFoundException) {
                log.warn("screen.carplay_failed", "error" to e.message)
                false
            }

        /** Whether one of this app's screens is what the rider sees (its process is foreground). */
        private fun appInFront(): Boolean {
            val process = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(process)
            return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

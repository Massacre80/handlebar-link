package com.abid.handlebarlink.controls

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.ride.RideStarter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * What the phone shows: CarPlay (CarLink) or this app, from a handlebar press while either is in front.
 * Android lets the app start activities from the background because it is the bike's companion app and,
 * once the rider allows it, because it may display over other apps (developer.android.com, "Activity
 * security", exceptions: SYSTEM_ALERT_WINDOW). A refused start only shows in the system log ("Background
 * activity launch blocked").
 */
class ScreenSwitcher
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val rides: RideStarter,
        logger: Logger,
    ) {
        private val log = logger.child("control")

        /** CarLink's screen; false when CarLink is not installed. */
        fun openCarPlay(): Boolean {
            val launch =
                CARLINK_PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
            if (launch == null) {
                log.warn("screen.no_carlink")
                return false
            }
            return try {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                log.info("screen.carplay")
                true
            } catch (e: ActivityNotFoundException) {
                log.warn("screen.carplay_failed", "error" to e.message)
                false
            }
        }

        /** This app's screen (as at ignition: over the lock screen). */
        fun openApp(): Boolean = rides.showOnScreen().also { log.info("screen.app", "asked" to it) }

        /** CarPlay when this app is in front, else this app. */
        fun switch(): Boolean = if (appInFront()) openCarPlay() else openApp()

        /** Whether one of this app's screens is what the rider sees (its process is foreground). */
        private fun appInFront(): Boolean {
            val process = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(process)
            return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

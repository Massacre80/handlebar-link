package com.abid.handlebarlink.ride

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.abid.handlebarlink.MainActivity
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Starts the ride service while the app is not on screen; tests replace it with a recording fake. */
interface RideStarter {
    /**
     * Starts the ride as a foreground service from the background. [presentHint]: there is a fresh sign the
     * bike is in range (connect directly). False when Android does not allow it now.
     */
    fun startInBackground(presentHint: Boolean): Boolean

    /**
     * Brings the app on screen for the ride (the phone is the bike's display): screen on, over the lock
     * screen. Android decides whether a background start is allowed; false only when it could not be asked.
     */
    fun showOnScreen(): Boolean
}

/** [RideStarter] with [RideService.startInBackground]. */
class AndroidRideStarter
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : RideStarter {
        private val log = logger.child("service")

        override fun startInBackground(presentHint: Boolean): Boolean =
            RideService.startInBackground(context, presentHint, log)

        // A refusal is not reported to the app; Android logs "Background activity launch blocked".
        override fun showOnScreen(): Boolean =
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(EXTRA_RIDE, true),
                )
                true
            } catch (e: ActivityNotFoundException) {
                log.warn("ride.screen.failed", "error" to e.message)
                false
            }
    }

/** Marks a start of [MainActivity] for a ride: it turns the screen on and shows over the lock screen. */
const val EXTRA_RIDE: String = "ride"

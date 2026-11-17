package com.abid.handlebarlink.ride

import android.content.Context
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Starts the ride service while the app is not on screen; tests replace it with a recording fake. */
fun interface RideStarter {
    /**
     * Starts the ride as a foreground service from the background. [presentHint]: there is a fresh sign the
     * bike is in range (connect directly). False when Android does not allow it now.
     */
    fun startInBackground(presentHint: Boolean): Boolean
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
    }

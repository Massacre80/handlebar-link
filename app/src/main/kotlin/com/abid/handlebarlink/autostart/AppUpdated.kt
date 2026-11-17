package com.abid.handlebarlink.autostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.di.ApplicationScope
import com.abid.handlebarlink.ride.RideStarter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * After an app update (docs/PLAN.md M4.3). The update killed the process, and with it a running ride; Android
 * does not report the bike again while it stays present, so nothing else would resume the ride before the
 * next ignition cycle (02 §3.4 "App update"). With a bike selected, the ride starts again without a presence
 * hint: it reconnects if the bike is there, and a presence-driven link lingers out if it is not. Observation
 * needs no action here: the process start for this broadcast runs [PresenceArming] (Application.onCreate).
 */
class AppUpdated
    @Inject
    constructor(
        private val settings: BikeSettings,
        private val rides: RideStarter,
        logger: Logger,
    ) {
        private val log = logger.child("autostart")

        /** Resumes the ride if a bike is selected. */
        suspend fun resume() {
            if (settings.bike.first() == null) {
                log.info("update.no_bike")
                return
            }
            val started = rides.startInBackground(presentHint = false)
            log.info("update.ride_start", "started" to started)
        }
    }

/**
 * Receives MY_PACKAGE_REPLACED, which the system sends only to the updated app itself, so the receiver is not
 * exported (android-intent-security: system broadcasts are protected). A foreground service may start from
 * this broadcast (FGS background-start exemptions).
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    /** What the receiver needs from the app's graph (Hilt entry point, as HeadsetConnectionReceiver). */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun appUpdated(): AppUpdated

        @ApplicationScope
        fun scope(): CoroutineScope
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = EntryPointAccessors.fromApplication(context, Dependencies::class.java)
        // Keep the broadcast until the setting is read and the start requested (well under its 10 s).
        val pending = goAsync()
        app.scope().launch {
            try {
                app.appUpdated().resume()
            } finally {
                pending.finish()
            }
        }
    }
}

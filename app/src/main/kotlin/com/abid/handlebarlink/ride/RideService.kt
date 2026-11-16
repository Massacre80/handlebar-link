package com.abid.handlebarlink.ride

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.abid.handlebarlink.bike.link.LinkState
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process and the Bluetooth link alive during a ride: a `connectedDevice` foreground service
 * (architecture review M2.4–M3-8). Started by the app while it is on screen; ends itself when the
 * ride ends. Not exported: only this app starts it, with explicit intents.
 */
@AndroidEntryPoint
class RideService : LifecycleService() {
    @Inject lateinit var coordinator: RideCoordinator

    @Inject lateinit var notifications: RideNotifications

    @Inject lateinit var logger: Logger

    private val log by lazy { logger.child("service") }
    private var ride: Job? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRide()
            ACTION_STOP -> coordinator.stop()
            else -> log.warn("service.unknown_action", "action" to intent?.action)
        }
        if (ride == null) stopSelf()
        // Never restarted by the system: a restart from the background may not start a foreground
        // service (FGS-LAUNCH); waking on ignition is the companion-device presence of M4.
        return START_NOT_STICKY
    }

    private fun startRide() {
        if (ride != null || !promote()) return
        notifications.clearProblem()
        ride = lifecycleScope.launch { runRide() }
    }

    /** Becomes a foreground service; false when Android does not allow it now. */
    private fun promote(): Boolean {
        // FGS-TYPES, connectedDevice: BLUETOOTH_CONNECT must be granted before startForeground.
        val permitted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            log.warn("service.no_permission")
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this,
                RideNotifications.ID_RIDE,
                notifications.riding(statusText(LinkState.Stopped)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            log.warn("service.foreground_refused", "error" to e.message)
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    private suspend fun runRide() {
        val updates =
            lifecycleScope.launch {
                coordinator.link
                    .filterNotNull()
                    .flatMapLatest { it.state }
                    .map(::statusText)
                    .distinctUntilChanged()
                    .collect(notifications::update)
            }
        try {
            coordinator.ride()?.let(notifications::showProblem)
        } finally {
            updates.cancel()
            ride = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Intents for this service; always explicit. */
    companion object {
        private const val ACTION_START = "com.abid.handlebarlink.ride.START"
        private const val ACTION_STOP = "com.abid.handlebarlink.ride.STOP"

        /** Starts a ride; call while the app is on screen (a foreground service may not start from the background). */
        fun start(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_START))
        }

        /** The intent that ends the ride. */
        fun stopIntent(context: Context): Intent = Intent(context, RideService::class.java).setAction(ACTION_STOP)
    }
}

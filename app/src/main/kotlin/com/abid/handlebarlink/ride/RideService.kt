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
import com.abid.handlebarlink.callaudio.IntercomCallRoute
import com.abid.handlebarlink.callaudio.VoipCallWatcher
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process and the Bluetooth link alive during a ride: a `connectedDevice` foreground service
 * (architecture review M2.4–M3-8). Started by the app while it is on screen, and from the background when
 * Android reports the bike nearby (autostart/, docs/PLAN.md M4); ends itself when the ride ends. Not
 * exported: only this app starts it, with explicit intents.
 */
@AndroidEntryPoint
class RideService : LifecycleService() {
    @Inject lateinit var coordinator: RideCoordinator

    @Inject lateinit var notifications: RideNotifications

    @Inject lateinit var logger: Logger

    @Inject lateinit var calls: VoipCallWatcher

    @Inject lateinit var callRoute: IntercomCallRoute

    private val log by lazy { logger.child("service") }
    private var ride: Job? = null

    @androidx.annotation.StringRes private var status: Int = statusText(LinkState.Stopped)
    private val endCall = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            // Restarted by the system after the process was killed during a ride (START_STICKY, edge case 6).
            // The bike may have gone meanwhile: no presence hint, so a presence-driven link lingers out.
            log.info("service.restarted")
            startRide(presentHint = false, foregroundStart = false)
        } else {
            handle(intent)
        }
        if (ride == null) stopSelf()
        // Restarted by the system after the process is killed mid-ride (am kill, low memory). The restart may
        // become a foreground service from the background thanks to the companion-device exemption; without
        // an association it is refused (promote) and the service stops, so the system does not restart it
        // again. A service stopped with stopSelf (ride over) is never restarted.
        return START_STICKY
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                startRide(
                    presentHint = intent.getBooleanExtra(EXTRA_PRESENT_HINT, true),
                    foregroundStart = intent.getBooleanExtra(EXTRA_FOREGROUND_START, false),
                )
            }

            ACTION_STOP -> {
                coordinator.stop()
            }

            ACTION_END_CALL_AUDIO -> {
                endCall.tryEmit(Unit)
            }

            else -> {
                log.warn("service.unknown_action", "action" to intent.action)
            }
        }
    }

    private fun startRide(
        presentHint: Boolean,
        foregroundStart: Boolean,
    ) {
        if (ride != null) {
            // startForegroundService on a running ride: post the foreground notification again, as that start
            // requires (FGS-LAUNCH: startForeground after every startForegroundService).
            if (foregroundStart) showForeground()
            return
        }
        if (!promote()) return
        notifications.clearProblem()
        ride = lifecycleScope.launch { runRide(presentHint) }
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
    private suspend fun runRide(presentHint: Boolean) {
        val updates =
            lifecycleScope.launch {
                coordinator.link
                    .filterNotNull()
                    .flatMapLatest { it.state }
                    .map(::statusText)
                    .distinctUntilChanged()
                    .collect {
                        status = it
                        if (!holdingCall) notifications.update(it)
                    }
            }
        val callAudio = lifecycleScope.launch { routeCalls() }
        try {
            coordinator.ride(presentHint)?.let(notifications::showProblem)
        } finally {
            callAudio.cancel()
            updates.cancel()
            ride = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private var holdingCall = false

    /**
     * While another app's call runs (CarLink's CarPlay call), holds a Telecom call so Android carries its
     * sound and microphone over the intercom (callaudio/). The service shows the call notification and adds
     * the phoneCall type meanwhile, as Core-Telecom asks for calls held in the background.
     */
    private suspend fun routeCalls() {
        val inCall = calls.inCall().stateIn(lifecycleScope)
        inCall.collect { call ->
            if (!call) return@collect
            log.info("callaudio.call.start")
            asCall(true)
            callRoute.hold { merge(inCall.filter { !it }.map {}, endCall).first() }
            asCall(false)
            log.info("callaudio.call.end")
        }
    }

    /** Switches the foreground notification and types between the ride and a held call. */
    private fun asCall(on: Boolean) {
        holdingCall = on
        showForeground()
    }

    /** Posts the foreground notification and types for what the service is doing now (ride or held call). */
    private fun showForeground() {
        try {
            if (holdingCall) {
                ServiceCompat.startForeground(
                    this,
                    RideNotifications.ID_RIDE,
                    notifications.callAudio(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
                )
            } else {
                ServiceCompat.startForeground(
                    this,
                    RideNotifications.ID_RIDE,
                    notifications.riding(status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            log.warn("service.foreground_refused", "call" to holdingCall, "error" to e.message)
        } catch (e: SecurityException) {
            log.warn("service.foreground_denied", "call" to holdingCall, "error" to e.message)
        }
    }

    /** Intents for this service; always explicit. */
    companion object {
        private const val ACTION_START = "com.abid.handlebarlink.ride.START"
        private const val ACTION_STOP = "com.abid.handlebarlink.ride.STOP"
        private const val ACTION_END_CALL_AUDIO = "com.abid.handlebarlink.ride.END_CALL_AUDIO"
        private const val EXTRA_PRESENT_HINT = "present_hint"
        private const val EXTRA_FOREGROUND_START = "foreground_start"

        /** Starts a ride; call while the app is on screen (a foreground service may not start from the background). */
        fun start(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_START))
        }

        /**
         * Starts a ride from the background, allowed because the app has a companion-device association and
         * holds REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND (FGS background-start exemptions).
         * [presentHint]: the bike was just reported nearby. False when Android refused, or when Nearby devices
         * is not allowed: then the service could not call startForeground (connectedDevice needs
         * BLUETOOTH_CONNECT, FGS-TYPES), which startForegroundService requires, so it is not started at all.
         */
        fun startInBackground(
            context: Context,
            presentHint: Boolean,
            log: Logger,
        ): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                log.warn("service.background_no_permission")
                return false
            }
            val intent =
                Intent(context, RideService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PRESENT_HINT, presentHint)
                    .putExtra(EXTRA_FOREGROUND_START, true)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                log.warn("service.background_refused", "error" to e.message)
                false
            }
        }

        /** The intent that stops holding a call (the call notification's hang-up). */
        fun endCallAudioIntent(context: Context): Intent =
            Intent(context, RideService::class.java).setAction(ACTION_END_CALL_AUDIO)

        /** The intent that ends the ride. */
        fun stopIntent(context: Context): Intent = Intent(context, RideService::class.java).setAction(ACTION_STOP)
    }
}

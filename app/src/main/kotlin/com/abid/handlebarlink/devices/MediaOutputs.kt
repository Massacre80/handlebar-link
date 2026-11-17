package com.abid.handlebarlink.devices

import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Choosing where media plays, Android 14+ (research of 2026-11-18): MediaRouter2 lists the system's media
 * routes to an app holding BLUETOOTH_CONNECT and BLUETOOTH_SCAN (MediaRouter2ServiceImpl's "system routing
 * permission"), and moving to one moves all media, CarPlay's too. Earlier releases needed a system
 * permission, so [AndroidAudioRouting] only builds this on 14+.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class MediaOutputs(
    private val context: Context,
    private val log: Logger,
) {
    /** The system's media outputs and the one playing now, while collected. */
    fun outputs(): Flow<List<MediaOutput>> =
        callbackFlow {
            val router = MediaRouter2.getInstance(context)

            fun now(): List<MediaOutput> {
                val selected =
                    router.systemController.selectedRoutes
                        .map { it.id }
                        .toSet()
                return router.routes
                    .filter { it.isSystemRoute }
                    .map { MediaOutput(it.id, it.name.toString(), it.type in BLUETOOTH, it.id in selected) }
            }
            val routes =
                object : MediaRouter2.RouteCallback() {
                    override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
                        trySend(now())
                    }
                }
            val transfers =
                object : MediaRouter2.TransferCallback() {
                    override fun onTransfer(
                        oldController: MediaRouter2.RoutingController,
                        newController: MediaRouter2.RoutingController,
                    ) {
                        trySend(now())
                    }
                }
            val main = ContextCompat.getMainExecutor(context)
            router.registerRouteCallback(main, routes, LIVE_AUDIO)
            router.registerTransferCallback(main, transfers)
            trySend(now())
            awaitClose {
                router.unregisterRouteCallback(routes)
                router.unregisterTransferCallback(transfers)
            }
        }.distinctUntilChanged()

    /** Moves all media to the output [id]; false when it is gone or not allowed. */
    fun playOn(id: String): Boolean {
        val router = MediaRouter2.getInstance(context)
        val route = router.routes.firstOrNull { it.isSystemRoute && it.id == id }
        return try {
            route?.let(router::transferTo)
            log.info("routing.play_on", "found" to (route != null), "type" to route?.type)
            route != null
        } catch (e: SecurityException) {
            log.warn("routing.play_on.denied", "error" to e.message)
            false
        }
    }

    private companion object {
        val BLUETOOTH =
            setOf(
                MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
                MediaRoute2Info.TYPE_BLE_HEADSET,
                MediaRoute2Info.TYPE_HEARING_AID,
            )
        val LIVE_AUDIO: RouteDiscoveryPreference =
            RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build()
    }
}

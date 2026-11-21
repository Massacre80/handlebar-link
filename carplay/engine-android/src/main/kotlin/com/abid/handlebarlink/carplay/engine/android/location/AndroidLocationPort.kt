package com.abid.handlebarlink.carplay.engine.android.location

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.spi.LocationPort
import com.abid.handlebarlink.carplay.protocol.location.CarPlayLocationFix
import com.abid.handlebarlink.core.common.log.Logger

/**
 * The Poco's own GPS as a [LocationPort] (C6 location): while the iPhone is subscribed, the bike-mounted phone's fix
 * is forwarded so the iPhone navigates off it instead of a pocketed phone's. GPS runs only between [start] and [stop]
 * (the iPhone's 0xFFFA/0xFFFC), never in the background. Without `ACCESS_FINE_LOCATION` or an enabled provider
 * [start] returns false, so the receiver sends nothing and the iPhone falls back to its own GPS (as before C6).
 * Updates arrive on the main looper and are cached; [latestFix] is read from the engine dispatcher, so [latest] is
 * volatile.
 */
internal class AndroidLocationPort(
    private val platform: AndroidPlatform,
    logger: Logger,
) : LocationPort {
    private val log = logger.child("location")

    @Volatile private var latest: CarPlayLocationFix? = null

    private val listener = LocationListener { location -> latest = location.toFix() }

    // Guard clauses for a platform API; the ACCESS_FINE_LOCATION check stays inline, immediately before the location
    // calls (nothing between them), so lint (and AGENTS rule 11) sees the permission at the adapter, no @SuppressLint.
    @Suppress("ReturnCount")
    override fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(platform.context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log.warn("location.permission_missing")
            return false
        }
        val manager = platform.context.getSystemService(LocationManager::class.java) ?: return false
        val provider = provider(manager) ?: return false
        return try {
            manager.getLastKnownLocation(provider)?.let { latest = it.toFix() }
            manager.requestLocationUpdates(
                provider,
                MIN_INTERVAL_MILLIS,
                MIN_DISTANCE_METERS,
                listener,
                Looper.getMainLooper(),
            )
            log.info("location.start", "provider" to provider)
            true
        } catch (e: SecurityException) {
            // The permission was revoked between the check above and here.
            log.warn("location.denied", "error" to e.message)
            false
        }
    }

    override fun stop() {
        platform.context.getSystemService(LocationManager::class.java)?.removeUpdates(listener)
        latest = null
        log.info("location.stop")
    }

    override fun latestFix(): CarPlayLocationFix? = latest

    /** GPS where it is on (the sky view the bike has), else the fused provider (API 31+), else none. */
    private fun provider(manager: LocationManager): String? =
        when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER

            LocationManager.FUSED_PROVIDER in manager.allProviders &&
                manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER

            else -> null
        }

    private fun Location.toFix(): CarPlayLocationFix =
        CarPlayLocationFix(
            latitudeDegrees = latitude,
            longitudeDegrees = longitude,
            timestampMillis = time,
            altitudeMeters = if (hasAltitude()) altitude else null,
            bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
            speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        )

    private companion object {
        /** One fix a second (DiPlay's poll rate); no minimum distance, so a stationary bike still refreshes time. */
        const val MIN_INTERVAL_MILLIS = 1_000L
        const val MIN_DISTANCE_METERS = 0f
    }
}

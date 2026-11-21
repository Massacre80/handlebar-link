package com.abid.handlebarlink.ui.settings

import android.Manifest
import androidx.annotation.StringRes
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.carPlayPermissions
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.nearbyPermissions
import com.abid.handlebarlink.ui.permissions.notificationPermissions

/**
 * The rows of Settings › Permissions, in their order (CarPlay's last): what each is and why the app needs it. Allow
 * all walks the same rows (AppRoot's rememberAllAccess builds one state per entry, by [ordinal]). [LOCATION] is
 * optional (C6 location): granting it makes the Poco forward its GPS to the iPhone; without it BikePlay still works
 * and the iPhone uses its own GPS, so it is not in [carPlayPermissions] and never blocks the connection. The location
 * Wi-Fi Direct needs on Android 12/12L is part of CarPlay's row ([carPlayPermissions]).
 */
internal enum class SettingsPermission(
    @param:StringRes val title: Int,
    @param:StringRes val why: Int,
) {
    NEARBY(R.string.permission_nearby, R.string.permission_nearby_why),
    NOTIFICATIONS(R.string.permission_notifications, R.string.permission_notifications_why),
    OVERLAY(R.string.permission_overlay, R.string.permission_overlay_why),
    BRIGHTNESS(R.string.permission_brightness, R.string.permission_brightness_why),
    CARPLAY(R.string.permission_carplay, R.string.permission_carplay_why),
    LOCATION(R.string.permission_location, R.string.permission_location_why),
}

/**
 * The runtime permissions this row asks Android for on release [sdk], in one request (empty: nothing to ask there, so
 * always allowed); null for a special access, which the rider switches on in its own Android screen.
 */
internal fun SettingsPermission.runtime(sdk: Int): List<String>? =
    when (this) {
        SettingsPermission.NEARBY -> {
            nearbyPermissions()
        }

        SettingsPermission.NOTIFICATIONS -> {
            notificationPermissions(sdk)
        }

        SettingsPermission.OVERLAY, SettingsPermission.BRIGHTNESS -> {
            null
        }

        SettingsPermission.CARPLAY -> {
            carPlayPermissions(sdk)
        }

        SettingsPermission.LOCATION -> {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

/** Where [permission]'s row stands in [this]. */
internal operator fun SettingsAccess.get(permission: SettingsPermission): Access =
    when (permission) {
        SettingsPermission.NEARBY -> nearby
        SettingsPermission.NOTIFICATIONS -> notifications
        SettingsPermission.OVERLAY -> overlay
        SettingsPermission.BRIGHTNESS -> brightness
        SettingsPermission.CARPLAY -> carPlay
        SettingsPermission.LOCATION -> location
    }

/** The permissions in Settings' order ([SettingsPermission]). */
internal fun SettingsAccess.all(): List<Access> = SettingsPermission.entries.map { this[it] }

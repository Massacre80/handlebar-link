package com.abid.handlebarlink.ui.settings

import android.os.Build
import androidx.compose.runtime.Composable
import com.abid.handlebarlink.ui.permissions.AccessState
import com.abid.handlebarlink.ui.permissions.rememberBrightnessAccess
import com.abid.handlebarlink.ui.permissions.rememberOverlayAccess
import com.abid.handlebarlink.ui.permissions.rememberRuntimeAccess

/** The permissions Settings shows (each re-read on every resume), CarPlay's last. */
internal class AllAccess(
    /** One per [SettingsPermission], by its ordinal: the index [PermissionsScreen] acts on. */
    val inOrder: List<AccessState>,
) {
    val nearby: AccessState get() = this[SettingsPermission.NEARBY]

    operator fun get(permission: SettingsPermission): AccessState = inOrder[permission.ordinal]

    fun statuses() =
        SettingsAccess(
            nearby = this[SettingsPermission.NEARBY].status,
            notifications = this[SettingsPermission.NOTIFICATIONS].status,
            overlay = this[SettingsPermission.OVERLAY].status,
            brightness = this[SettingsPermission.BRIGHTNESS].status,
            carPlay = this[SettingsPermission.CARPLAY].status,
            location = this[SettingsPermission.LOCATION].status,
        )
}

/** One live state per [SettingsPermission], each re-read on every resume. */
@Composable
internal fun rememberAllAccess() = AllAccess(SettingsPermission.entries.map { rememberAccess(it) })

/**
 * The live state of [permission]'s row: its special access's own screen, or its runtime permissions
 * ([SettingsPermission.runtime], which is also what Allow all asks for; an empty list is always allowed).
 */
@Composable
private fun rememberAccess(permission: SettingsPermission): AccessState =
    when (permission) {
        SettingsPermission.OVERLAY -> rememberOverlayAccess()
        SettingsPermission.BRIGHTNESS -> rememberBrightnessAccess()
        else -> rememberRuntimeAccess(permission.runtime(Build.VERSION.SDK_INT).orEmpty())
    }

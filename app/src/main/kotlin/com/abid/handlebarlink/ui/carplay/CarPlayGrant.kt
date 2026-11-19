package com.abid.handlebarlink.ui.carplay

import android.Manifest
import android.annotation.SuppressLint
import androidx.annotation.StringRes
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.ui.permissions.Access

// CarPlay's missing permissions in the rider's words (owner, 2026-11-19: CarPlay never runs without them, and nothing
// fails silently): the status line, the hero, Home's card and the engine's error each name what to allow.

/**
 * One thing the rider allows for CarPlay, with its [label] in a list, the hero's [title] and one-line [detail], the
 * status line's [line] and the [ask] line that explains it before Android's dialog.
 */
enum class CarPlayGrant(
    @param:StringRes val label: Int,
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
    @param:StringRes val line: Int,
    @param:StringRes val ask: Int,
) {
    /** Bluetooth and Nearby Wi-Fi (precise location on Android 12): Android's "Nearby devices". */
    NEARBY(
        R.string.carplay_access_nearby,
        R.string.carplay_tab_permission_title,
        R.string.carplay_tab_permission_detail,
        R.string.carplay_status_permission,
        R.string.carplay_ask_nearby,
    ),

    /**
     * The local network (Android 17), for the sockets to the iPhone on the Wi-Fi Direct group. Android lists it under
     * Nearby devices (local-network-permission guide: "Part of the existing NEARBY_DEVICES permission group").
     */
    LOCAL_NETWORK(
        R.string.carplay_access_local_network,
        R.string.carplay_tab_local_network_title,
        R.string.carplay_tab_local_network_detail,
        R.string.carplay_status_permission_local_network,
        R.string.carplay_ask_local_network,
    ),

    /** The microphone: Siri and calls. */
    MICROPHONE(
        R.string.carplay_access_microphone,
        R.string.carplay_tab_microphone_title,
        R.string.carplay_tab_microphone_detail,
        R.string.carplay_status_permission_microphone,
        R.string.carplay_ask_microphone,
    ),
}

/** What allowing [permission] is called for the rider. */
@SuppressLint("InlinedApi") // Compares names only; the local network's constant is copied in at compile time.
internal fun carPlayGrant(permission: String): CarPlayGrant =
    when (permission) {
        Manifest.permission.RECORD_AUDIO -> CarPlayGrant.MICROPHONE
        Manifest.permission.ACCESS_LOCAL_NETWORK -> CarPlayGrant.LOCAL_NETWORK
        else -> CarPlayGrant.NEARBY
    }

/** What the [missing] permissions are called, each once, in a fixed order (Nearby devices, local network, mic). */
internal fun missingAccess(missing: List<String>): List<CarPlayGrant> {
    val named = missing.map(::carPlayGrant).toSet()
    return CarPlayGrant.entries.filter { it in named }
}

/** The status line for [missing] permissions: it names the one to allow, or all of them. */
internal fun permissionLine(missing: List<String>): CarPlayLine {
    val access = missingAccess(missing)
    return if (access.size == 1) {
        CarPlayLine(access.single().line, tone = SignalTone.ERROR)
    } else {
        CarPlayLine(
            R.string.carplay_status_permission_several,
            parts = access.map { it.label },
            tone = SignalTone.ERROR,
        )
    }
}

/** The engine's missing [permission], in the rider's words. */
@StringRes
internal fun permissionErrorText(permission: String): Int =
    when (carPlayGrant(permission)) {
        CarPlayGrant.NEARBY -> R.string.carplay_error_permission
        CarPlayGrant.LOCAL_NETWORK -> R.string.carplay_error_permission_local_network
        CarPlayGrant.MICROPHONE -> R.string.carplay_error_permission_microphone
    }

/**
 * Needs you for missing permissions ([grants], never empty): one names itself and why ("Allow the microphone", "Siri
 * and calls use the microphone…"); several are named together. One action, Allow ([withAccess] makes it Open settings
 * once Android no longer asks).
 */
internal fun needsAccess(grants: List<CarPlayGrant>): CarPlayHero =
    if (grants.size == 1) {
        needsYou(grants.single().title, grants.single().detail, TabAction.ALLOW)
    } else {
        needsYou(R.string.carplay_tab_access_title, HeroDetail.Missing(grants), TabAction.ALLOW)
    }

/**
 * The hero's Allow as Android still allows it ([access], the CarPlay permissions' state, read fresh on every resume):
 * Allow asks again while Android may still show its dialog, Open settings once it no longer does (refused for good).
 */
fun CarPlayHero.withAccess(access: Access): CarPlayHero =
    if (primary == TabAction.ALLOW && access == Access.BLOCKED) copy(primary = TabAction.OPEN_SETTINGS) else this

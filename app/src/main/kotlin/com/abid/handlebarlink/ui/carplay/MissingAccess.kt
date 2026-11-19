package com.abid.handlebarlink.ui.carplay

import android.Manifest
import androidx.annotation.StringRes
import com.abid.handlebarlink.R

// CarPlay's missing permissions in the rider's words: the status line, the hero and the engine's error name the one to
// allow (the microphone for Siri and calls, or Nearby devices for the rest).

/** Which of CarPlay's permissions are [missing], in the rider's words. */
internal enum class MissingAccess {
    /** Bluetooth, Nearby Wi-Fi or the local network (the Nearby devices group; location on Android 12). */
    NEARBY,

    /** Only the microphone, for Siri and calls. */
    MICROPHONE,

    /** Both. */
    BOTH,
}

/** What is [missing]: the microphone, the Nearby devices group (all the others) or both. */
internal fun missingAccess(missing: List<String>): MissingAccess {
    val microphone = Manifest.permission.RECORD_AUDIO in missing
    val nearby = missing.any { it != Manifest.permission.RECORD_AUDIO }
    return when {
        microphone && nearby -> MissingAccess.BOTH
        microphone -> MissingAccess.MICROPHONE
        else -> MissingAccess.NEARBY
    }
}

/** The status line for [missing] permissions: it names the one to allow. */
@StringRes
internal fun permissionLine(missing: List<String>): Int =
    when (missingAccess(missing)) {
        MissingAccess.NEARBY -> R.string.carplay_status_permission
        MissingAccess.MICROPHONE -> R.string.carplay_status_permission_microphone
        MissingAccess.BOTH -> R.string.carplay_status_permission_both
    }

/** The engine's missing [permission], in the rider's words: the microphone, or Nearby devices for the rest. */
@StringRes
internal fun permissionErrorText(permission: String): Int =
    if (permission == Manifest.permission.RECORD_AUDIO) {
        R.string.carplay_error_permission_microphone
    } else {
        R.string.carplay_error_permission
    }

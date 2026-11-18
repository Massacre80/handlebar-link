package com.abid.handlebarlink.ui.permissions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * "Display over other apps", which lets the app show what a handlebar press did on top of CarPlay
 * (ui/overlay/PressBanner). A special access, not a runtime permission: the rider switches it on in Android's
 * page for the app.
 */
@Composable
fun rememberOverlayAccess(): AccessState {
    val context = LocalContext.current

    fun read() = if (Settings.canDrawOverlays(context)) Access.GRANTED else Access.ASK
    val state = remember { AccessState(read()) }
    val screen = {
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
    }
    state.request = AccessRequest.Screen(screen)
    state.read = ::read
    state.onAct = {
        context.startActivity(screen().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is Activity } ?: 0))
    }
    LifecycleResumeEffect(Unit) {
        state.status = read()
        onPauseOrDispose {}
    }
    return state
}

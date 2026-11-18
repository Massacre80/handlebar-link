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
 * "Modify system settings", which lets the handlebar change the screen's brightness. A special access, not a
 * runtime permission: the rider switches it on in Android's page for the app.
 */
@Composable
fun rememberBrightnessAccess(): AccessState {
    val context = LocalContext.current

    fun read() = if (Settings.System.canWrite(context)) Access.GRANTED else Access.ASK
    val state = remember { AccessState(read()) }
    val screen = { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", context.packageName, null)) }
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

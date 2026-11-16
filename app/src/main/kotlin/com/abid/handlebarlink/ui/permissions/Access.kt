package com.abid.handlebarlink.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.abid.handlebarlink.controls.MediaAccessListener

/** Where a permission stands (android-permissions-security §6: check, explain, request; never loop). */
enum class Access {
    /** Allowed. */
    GRANTED,

    /** Not allowed yet; asking shows the system dialog. */
    ASK,

    /** Refused for good: only the app's settings page can allow it now. */
    BLOCKED,
}

/** A permission's [status] (re-read whenever the app resumes) and the one right action for it. */
class AccessState internal constructor(
    status: Access,
) {
    /** The current status. */
    var status: Access by mutableStateOf(status)
        internal set

    /** Set by the composable that owns this state, each composition. */
    internal var onAct: () -> Unit = {}

    /** Asks (ASK), or opens the settings page (BLOCKED). */
    fun act() = onAct()
}

/**
 * A runtime permission. Asking twice after a refusal shows nothing, so once the system says no rationale
 * can be shown after the rider was asked, the state is BLOCKED and the action opens the app's settings.
 */
@Composable
fun rememberRuntimeAccess(permission: String): AccessState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var asked by rememberSaveable(permission) { mutableStateOf(false) }

    fun read(): Access =
        when {
            granted(context, permission) -> {
                Access.GRANTED
            }

            asked && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) -> {
                Access.BLOCKED
            }

            else -> {
                Access.ASK
            }
        }
    val state = remember(permission) { AccessState(read()) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            asked = true
            state.status = read()
        }
    state.onAct = {
        when (state.status) {
            Access.ASK -> {
                launcher.launch(permission)
            }

            Access.BLOCKED -> {
                context.startActivity(appSettings(context))
            }

            Access.GRANTED -> {}
        }
    }
    LifecycleResumeEffect(permission) {
        state.status = read()
        onPauseOrDispose {}
    }
    return state
}

/** Bluetooth to the meter. */
@Composable
fun rememberNearbyAccess(): AccessState = rememberRuntimeAccess(Manifest.permission.BLUETOOTH_CONNECT)

/** The ride and problem notifications: a runtime permission from Android 13, always allowed before. */
@Composable
fun rememberNotificationAccess(): AccessState =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberRuntimeAccess(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        remember { AccessState(Access.GRANTED) }
    }

/**
 * Notification access, which lets the handlebar reach CarLink's media session (interim, controls/). Not a
 * runtime permission: the rider switches it on in Android's settings page for our listener.
 */
@Composable
fun rememberMediaAccess(): AccessState {
    val context = LocalContext.current

    fun read() =
        if (NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            Access.GRANTED
        } else {
            Access.ASK
        }
    val state = remember { AccessState(read()) }
    state.onAct = { context.startActivity(listenerSettings(context)) }
    LifecycleResumeEffect(Unit) {
        state.status = read()
        onPauseOrDispose {}
    }
    return state
}

private fun granted(
    context: Context,
    permission: String,
) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun appSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is Activity } ?: 0)

private fun listenerSettings(context: Context): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, MediaAccessListener::class.java).flattenToString(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is Activity } ?: 0)

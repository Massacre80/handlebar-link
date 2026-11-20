package com.abid.handlebarlink.ui.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Where a permission stands (android-permissions-security §6: check, explain, request; never loop). */
enum class Access {
    /** Allowed. */
    GRANTED,

    /** Not allowed yet; asking shows the system dialog. */
    ASK,

    /** Refused for good: only the app's settings page can allow it now. */
    BLOCKED,
}

/** How a permission is asked for: Android's runtime dialog, or the Android screen that switches it on. */
internal sealed interface AccessRequest {
    /** Runtime [permissions], asked in one request with the others (Android shows its dialogs in turn). */
    data class Runtime(
        val permissions: List<String>,
    ) : AccessRequest

    /** Special access, switched on in the Android screen [intent] opens. */
    class Screen(
        val intent: () -> Intent,
    ) : AccessRequest

    /** Nothing to ask (always allowed on this Android version). */
    data object None : AccessRequest
}

/**
 * The runtime permissions this process has asked for, each on its own, shared by every screen: Android shows no
 * dialog for one refused for good, and only after it was asked once does "no rationale" mean that
 * (android-permissions-security §6). Shared so that asking on one screen (the CarPlay tab's explanation dialog) turns
 * Allow into Open settings on another at once. Kept in memory only; [rememberRuntimeAccess] also saves its own across
 * process death.
 */
internal object AskedPermissions {
    private val asked = HashSet<String>()

    /** Whether [permission] itself was asked for. */
    fun contains(permission: String): Boolean = synchronized(asked) { permission in asked }

    /** [permissions] were asked for. */
    fun add(permissions: List<String>) {
        synchronized(asked) { asked += permissions }
    }
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

    /** How Allow all asks for it (rememberAllowAll); set with [onAct]. */
    internal var request: AccessRequest = AccessRequest.None

    /** Reads the status from Android now; set with [onAct]. */
    internal var read: () -> Access = { this.status }

    /** The status read from Android now (results can arrive before the resume that re-reads it). */
    internal fun refresh(): Access = read().also { status = it }

    /** Asks (ASK), or opens the settings page (BLOCKED). */
    fun act() = onAct()
}

/**
 * Where runtime permissions stand from the [missing] ones: GRANTED when none is; BLOCKED when Android would show no
 * dialog for any of them, that is every missing permission was itself [asked] before and has no [rationale] now
 * (refused for good: android-permissions-security §6); else ASK. Decided per permission: one permission of a list
 * asked on another screen (Bluetooth by the pairing) says nothing about the others (the microphone, never asked), so
 * the list is still asked for. Without an activity ([rationale] null) nothing counts as refused for good.
 */
internal fun accessOf(
    missing: List<String>,
    asked: (String) -> Boolean,
    rationale: ((String) -> Boolean)?,
): Access =
    when {
        missing.isEmpty() -> Access.GRANTED
        rationale != null && missing.all { asked(it) && !rationale(it) } -> Access.BLOCKED
        else -> Access.ASK
    }

/**
 * Runtime permissions asked together (one system dialog when they share a group, as Nearby devices does).
 * Granted only when all are. Asking twice after a refusal shows nothing, so once the system says no rationale
 * can be shown for each missing permission after the rider was asked for it, the state is BLOCKED and the action
 * opens the app's settings ([accessOf]). [onResult] reports each answer (true: all granted).
 */
@Composable
fun rememberRuntimeAccess(
    permissions: List<String>,
    onResult: (Boolean) -> Unit = {},
): AccessState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var asked by rememberSaveable(permissions) { mutableStateOf(false) }

    fun read(): Access =
        accessOf(
            missing = permissions.filterNot { granted(context, it) },
            // Asked here (saved across process death: this list was asked as a whole), or on another screen.
            asked = { asked || AskedPermissions.contains(it) },
            rationale =
                activity?.let {
                    { permission ->
                        ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
                    }
                },
        )
    val state = remember(permissions) { AccessState(read()) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
            asked = true
            AskedPermissions.add(permissions)
            state.status = read()
            onResult(answers.values.all { it } && answers.keys.containsAll(permissions))
        }
    state.request = AccessRequest.Runtime(permissions)
    state.read = ::read
    state.onAct = {
        when (state.status) {
            Access.ASK -> {
                launcher.launch(permissions.toTypedArray())
            }

            Access.BLOCKED -> {
                context.startActivity(appSettings(context))
            }

            Access.GRANTED -> {}
        }
    }
    LifecycleResumeEffect(permissions) {
        state.status = read()
        onPauseOrDispose {}
    }
    return state
}

/** One runtime permission ([rememberRuntimeAccess] for a list). */
@Composable
fun rememberRuntimeAccess(permission: String): AccessState = rememberRuntimeAccess(listOf(permission))

/**
 * Searching for and pairing Bluetooth devices, scan and connect (both Nearby devices, one dialog): the meter
 * during setup, a new device on Pair a new device.
 */
@Composable
fun rememberSetupAccess(onResult: (Boolean) -> Unit): AccessState =
    rememberRuntimeAccess(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), onResult)

/** What [rememberNearbyAccess] asks for: Bluetooth to the meter. */
internal fun nearbyPermissions(): List<String> = listOf(Manifest.permission.BLUETOOTH_CONNECT)

/** Bluetooth to the meter. */
@Composable
fun rememberNearbyAccess(): AccessState = rememberRuntimeAccess(nearbyPermissions())

/**
 * What [rememberNotificationAccess] asks for on Android [sdk]: a runtime permission from Android 13, nothing before.
 * Lint's InlinedApi cannot see a guard on the [sdk] parameter; the guard is the `if`.
 */
@SuppressLint("InlinedApi") // A string constant copied in at compile time, added only when [sdk] has it.
internal fun notificationPermissions(sdk: Int): List<String> =
    if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

/** The ride and problem notifications: a runtime permission from Android 13, always allowed before. */
@Composable
fun rememberNotificationAccess(): AccessState {
    val permissions = notificationPermissions(Build.VERSION.SDK_INT)
    return if (permissions.isEmpty()) remember { AccessState(Access.GRANTED) } else rememberRuntimeAccess(permissions)
}

private fun granted(
    context: Context,
    permission: String,
) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun appSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is Activity } ?: 0)

package com.abid.handlebarlink.ui.carplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.ride.RideService
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.AccessState
import com.abid.handlebarlink.ui.permissions.rememberCarPlayAccess

/** What BikePlay's buttons ask of BikePlay itself (the BikePlay tab's and Home's view models). */
interface BikePlayCommands {
    /** Start BikePlay: a stop lifted, or a failed engine started again; the caller starts the ride service. */
    fun start()

    /** Cancel or Disconnect: BikePlay stops until started again. */
    fun stop()

    /** Try now: a retry waiting out its pause starts at once. */
    fun retryNow()

    /** A permission answer came back: BikePlay reads the permissions again. */
    fun recheckPermission()
}

/** BikePlay's buttons: what [onAction] does with each, and the permissions' [access] (Allow or Open settings). */
class TabActions(
    val access: Access,
    val onAction: (TabAction) -> Unit,
)

/**
 * The BikePlay tab's and Home's card's buttons, one place for both: pages through [navigation], BikePlay through
 * [commands], and the Activity's parts. Start BikePlay (after the rider stopped it, after an error, or with no ride
 * running) asks for BikePlay's missing permissions, the microphone included, through the same runtime flow as the
 * Permissions page, after a dialog that says why; it starts the ride service once they are allowed, so a ride that had
 * ended picks BikePlay up while the app is on screen (a foreground service may only start then). Refused, Needs you
 * shows Allow while Android still asks, Open settings once it no longer does ([withAccess]).
 */
@Composable
fun rememberTabActions(
    commands: BikePlayCommands,
    navigation: CarPlayNavigation,
): TabActions {
    val context = LocalContext.current
    val access = rememberCarPlayAccess { commands.recheckPermission() }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    return TabActions(access.status) { action ->
        when (action) {
            TabAction.ADD_IPHONE -> navigation.onAddIphone()
            TabAction.CHOOSE_IPHONE -> navigation.onIphone()
            TabAction.OPEN_CARPLAY -> navigation.onOpen()
            TabAction.START_CARPLAY -> commands.start().also { startRide(context, access, navigation.onExplainAccess) }
            TabAction.CANCEL, TabAction.DISCONNECT -> commands.stop()
            TabAction.RETRY_NOW -> commands.retryNow()
            else -> fix(action, context, access) { enableBluetooth.launch(it) }
        }
    }
}

/** Starts the ride service when BikePlay's permissions allow, or explains and asks for them ([explain]). */
private fun startRide(
    context: Context,
    access: AccessState,
    explain: () -> Unit,
) {
    when (access.status) {
        Access.GRANTED -> {
            RideService.start(context)
        }

        Access.ASK -> {
            explain()
        }

        // Refused for good: Needs you shows Open settings (withAccess).
        Access.BLOCKED -> {}
    }
}

/** A Needs you fix: the permission, the Wi-Fi panel, or Bluetooth's enable request ([launch]). */
private fun fix(
    action: TabAction,
    context: Context,
    access: AccessState,
    launch: (Intent) -> Unit,
) {
    when (action) {
        // Allow asks again while Android still shows its dialog; Open settings opens the app's settings page
        // (Settings.ACTION_APPLICATION_DETAILS_SETTINGS, rememberRuntimeAccess) once it no longer does.
        TabAction.ALLOW, TabAction.OPEN_SETTINGS -> {
            access.act()
        }

        TabAction.TURN_ON_WIFI -> {
            // The Wi-Fi panel over the app (Settings.Panel, Android 10+).
            context.startActivity(Intent(Settings.Panel.ACTION_WIFI))
        }

        TabAction.TURN_ON_BLUETOOTH -> {
            // ACTION_REQUEST_ENABLE needs BLUETOOTH_CONNECT, which BikePlay's permissions include.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                access.act()
            }
        }

        else -> {}
    }
}

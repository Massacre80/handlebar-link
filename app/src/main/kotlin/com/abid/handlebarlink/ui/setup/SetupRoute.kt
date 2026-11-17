package com.abid.handlebarlink.ui.setup

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import com.abid.handlebarlink.setup.SetupProblem
import com.abid.handlebarlink.setup.SetupStep
import com.abid.handlebarlink.setup.SetupViewModel
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.rememberLocationAccess
import com.abid.handlebarlink.ui.permissions.rememberNotificationAccess
import com.abid.handlebarlink.ui.permissions.rememberSetupAccess
import kotlinx.coroutines.delay

/**
 * Setup with its view model, scoped to this call site (ViewModel scoping APIs, "ViewModels scoped to a
 * composable"): each time setup is shown it starts fresh, and its state is cleared once a bike is saved.
 */
@Composable
fun SetupRoute() {
    CompositionLocalProvider(LocalViewModelStoreOwner provides rememberViewModelStoreOwner()) {
        SetupContent(hiltViewModel())
    }
}

/**
 * The parts of setup that need the Activity: Nearby devices (asked on Find my bike), Bluetooth's enable
 * request ("Set up Bluetooth"), Android's consent screen, notifications, and the system Back.
 */
@Composable
private fun SetupContent(viewModel: SetupViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nearby = rememberSetupAccess { granted -> if (granted) viewModel.findBike() else viewModel.permissionRefused() }
    val notifications = rememberNotificationAccess()
    val location = rememberLocationAccess()
    val access = SetupAccess(notifications.status, location.status)
    AutoHome(state.step == SetupStep.DONE && access.complete, viewModel::finish)
    val enableBluetooth =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) viewModel.findBike()
        }
    ConsentLauncher(state.consent, viewModel)
    BackHandler(enabled = state.step in STEPS_WITH_BACK) { viewModel.back() }

    fun find() = if (nearby.status == Access.GRANTED) viewModel.findBike() else nearby.act()
    SetupScreen(
        state,
        access,
        SetupActions(
            onStart = viewModel::start,
            onBack = { viewModel.back() },
            onFind = ::find,
            onFix = { problem ->
                when (problem) {
                    SetupProblem.BLUETOOTH_OFF -> {
                        if (connectAllowed(context)) {
                            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } else {
                            nearby.act()
                        }
                    }

                    SetupProblem.LOCATION_OFF -> {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }

                    SetupProblem.PERMISSION_MISSING -> {
                        find()
                    }

                    SetupProblem.SCAN_FAILED, SetupProblem.NOT_LINKED -> {
                        viewModel.search()
                    }

                    SetupProblem.PAIRING_FAILED -> {
                        viewModel.pair()
                    }
                }
            },
            onChoose = viewModel::choose,
            onSearchAgain = viewModel::search,
            onNotifications = notifications::act,
            onLocation = location::act,
            onHome = viewModel::finish,
        ),
    )
}

/** Launches Android's consent screen once when [consent] appears, and hands its answer to [viewModel]. */
@Composable
private fun ConsentLauncher(
    consent: IntentSender?,
    viewModel: SetupViewModel,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            viewModel.onConsentResult(it.resultCode, it.data)
        }
    LaunchedEffect(consent) {
        val sender = consent ?: return@LaunchedEffect
        launcher.launch(IntentSenderRequest.Builder(sender).build())
        viewModel.consentLaunched()
    }
}

/** "You're set" has no button: once [ready] (both permissions allowed), a short beat to see it, then Home. */
@Composable
private fun AutoHome(
    ready: Boolean,
    onHome: () -> Unit,
) {
    LaunchedEffect(ready) {
        if (ready) {
            delay(AUTO_HOME_MS)
            onHome()
        }
    }
}

/** Asking Android to turn Bluetooth on needs BLUETOOTH_CONNECT (BluetoothAdapter.ACTION_REQUEST_ENABLE). */
private fun connectAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

/** How long "You're set" shows both permissions allowed before Home opens. */
private const val AUTO_HOME_MS = 900L

/** Steps whose system Back goes one step back; on Welcome and "You're set" it leaves the app. */
private val STEPS_WITH_BACK = setOf(SetupStep.READY, SetupStep.CHOOSE, SetupStep.LINKING, SetupStep.PAIRING)

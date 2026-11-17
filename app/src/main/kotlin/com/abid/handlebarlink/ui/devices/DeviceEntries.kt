package com.abid.handlebarlink.ui.devices

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import com.abid.handlebarlink.devices.PairState
import com.abid.handlebarlink.devices.SavedDevice
import com.abid.handlebarlink.ui.navigation.AddDeviceRoute
import com.abid.handlebarlink.ui.navigation.DeviceSheetRoute
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.PairDeviceRoute
import com.abid.handlebarlink.ui.permissions.rememberNearbyAccess
import com.abid.handlebarlink.ui.permissions.rememberSetupAccess

/**
 * Add device and its "What is it?" sheet. The sheet is a Navigation 3 dialog destination
 * (DialogSceneStrategy): Add device stays under it, Back closes it, and it sits above the tab bar.
 */
fun EntryProviderScope<NavKey>.deviceEntries(navigator: Navigator) {
    entry<AddDeviceRoute> {
        val viewModel: AddDeviceViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val nearby = rememberNearbyAccess()
        val context = LocalContext.current
        val enable =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refresh() }
        // Back from Bluetooth settings, the permission dialog or the app's settings: read the list again.
        LifecycleResumeEffect(Unit) {
            viewModel.refresh()
            onPauseOrDispose {}
        }
        AddDeviceScreen(
            state,
            nearby.status,
            AddDeviceActions(
                onAllow = nearby::act,
                onTurnOn = {
                    // Asking to turn Bluetooth on needs BLUETOOTH_CONNECT (ACTION_REQUEST_ENABLE).
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } else {
                        nearby.act()
                    }
                },
                onPick = { navigator.navigate(it.toSheet()) },
                onPair = { navigator.navigate(PairDeviceRoute) },
                onBack = navigator::goBack,
            ),
        )
    }
    entry<PairDeviceRoute> { PairDeviceEntry(navigator) }
    entry<DeviceSheetRoute>(metadata = DialogSceneStrategy.dialog(SHEET)) { route ->
        val viewModel: DeviceSheetViewModel = hiltViewModel()
        val saved by viewModel.saved.collectAsStateWithLifecycle()
        var kind by rememberSaveable { mutableStateOf(route.kind) }
        val name = rememberTextFieldState(route.savedName ?: route.deviceName)
        LaunchedEffect(saved) { if (saved) navigator.goBackPast(AddDeviceRoute) }
        DeviceSheet(
            SheetDevice(route.deviceName, route.audio, route.connected),
            kind,
            onKind = { kind = it },
            name = name,
            onSave = { viewModel.save(SavedDevice(route.address, name.text.toString(), kind, route.deviceName)) },
            onCancel = navigator::goBack,
        )
    }
}

/** Pair a new device: searches while shown; a paired device goes straight to "What is it?". */
@Composable
private fun PairDeviceEntry(navigator: Navigator) {
    val viewModel: PairDeviceViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val access = rememberSetupAccess { granted -> if (granted) viewModel.searchAgain() }
    val enable =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.searchAgain() }
    // Back from the app's settings or Bluetooth settings with nothing to show yet: try again. Not while a list
    // is up: Android's pairing prompt pauses the page, and a new search would cut into the pairing.
    LifecycleResumeEffect(state is PairDeviceUiState.Nearby) {
        if (state !is PairDeviceUiState.Nearby) viewModel.searchAgain()
        onPauseOrDispose {}
    }
    val paired = (state as? PairDeviceUiState.Nearby)?.attempt?.takeIf { it.state == PairState.PAIRED }?.device
    LaunchedEffect(paired) {
        if (paired != null) {
            viewModel.handedOver()
            navigator.navigate(
                DeviceSheetRoute(paired.address, paired.name, null, paired.suggested, paired.audio, connected = false),
            )
        }
    }
    PairDeviceScreen(
        state,
        access.status,
        PairDeviceActions(
            onAllow = access::act,
            onTurnOn = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } else {
                    access.act()
                }
            },
            onPair = viewModel::pair,
            onSearchAgain = viewModel::searchAgain,
            onNotListed = { openBluetoothSettings(context) },
            onBack = navigator::goBack,
        ),
    )
}

private fun DeviceChoice.toSheet() =
    DeviceSheetRoute(
        address = address,
        deviceName = model,
        savedName = name.takeIf { added },
        kind = kind,
        audio = audio,
        connected = connected,
    )

/** Android's Bluetooth settings, where a new device is paired (no permission needed). */
private fun openBluetoothSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        // No Bluetooth settings page on this device: nothing to open.
    }
}

/** Full width and edge to edge: the sheet anchors itself to the bottom and pads for the bars and keyboard. */
private val SHEET = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

package com.abid.handlebarlink.ui.carplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.carPlayPermissions
import com.abid.handlebarlink.carplay.engine.android.pairing.CarPlayPairing
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.core.designsystem.component.HlDialog
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.ride.RideService
import com.abid.handlebarlink.ui.navigation.CarPlayAccessRoute
import com.abid.handlebarlink.ui.navigation.CarPlayDisplayRoute
import com.abid.handlebarlink.ui.navigation.CarPlayRoute
import com.abid.handlebarlink.ui.navigation.CarPlayScreenRoute
import com.abid.handlebarlink.ui.navigation.ForgetIphoneRoute
import com.abid.handlebarlink.ui.navigation.IphoneSheetRoute
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.PairIphoneRoute
import com.abid.handlebarlink.ui.navigation.sheet
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.AccessState
import com.abid.handlebarlink.ui.permissions.rememberCarPlayAccess
import com.abid.handlebarlink.ui.permissions.rememberRuntimeAccess
import kotlinx.coroutines.launch

/**
 * The CarPlay tab, the CarPlay display (full screen while connected), in-app iPhone pairing (a full page), the iPhone
 * sheet and its "Forget …?" confirmation (a dialog, as Intercom's "Remove …?"), the CarPlay screen sheet and the
 * dialog that explains CarPlay's permissions before Android asks.
 */
fun EntryProviderScope<NavKey>.carPlayEntries(
    navigator: Navigator,
    contentPadding: PaddingValues,
) {
    entry<CarPlayRoute> {
        CarPlayEntry(
            contentPadding,
            CarPlayNavigation(
                onOpen = { navigator.navigate(CarPlayDisplayRoute) },
                onAddIphone = { navigator.navigate(PairIphoneRoute) },
                onIphone = { navigator.navigate(IphoneSheetRoute) },
                onScreen = { navigator.navigate(CarPlayScreenRoute) },
                onExplainAccess = { navigator.navigate(CarPlayAccessRoute) },
            ),
        )
    }
    // A full page over the CarPlay tab (no tab bar); Back returns to the tab, CarPlay keeps running.
    entry<CarPlayDisplayRoute> { CarPlayDisplayEntry() }
    entry<PairIphoneRoute> { PairIphoneEntry(navigator) }
    entry<IphoneSheetRoute>(metadata = sheet()) { IphoneSheetEntry(navigator) }
    entry<ForgetIphoneRoute>(metadata = DialogSceneStrategy.dialog()) { route -> ForgetEntry(route, navigator) }
    entry<CarPlayScreenRoute>(metadata = sheet()) { CarPlayScreenEntry(navigator) }
    entry<CarPlayAccessRoute>(metadata = DialogSceneStrategy.dialog()) { CarPlayAccessEntry(navigator) }
}

/** What the CarPlay tab asks its entry to open. */
data class CarPlayNavigation(
    /** The CarPlay display (connected). */
    val onOpen: () -> Unit,
    /** In-app pairing ("Add iPhone"). */
    val onAddIphone: () -> Unit,
    /** The iPhone sheet (the iPhone row). */
    val onIphone: () -> Unit,
    /** The CarPlay screen sheet (the CarPlay screen row). */
    val onScreen: () -> Unit = {},
    /** The dialog that explains CarPlay's missing permissions, then asks for them. */
    val onExplainAccess: () -> Unit = {},
)

/** What the pairing screens can ask for; the entry turns each into the view model call or system screen. */
data class PairIphoneActions(
    val onBack: () -> Unit,
    /** Android's "make visible" dialog (asking for Nearby devices first when needed). */
    val onMakeVisible: () -> Unit,
    /** Nearby devices, when it is missing. */
    val onAllow: () -> Unit,
    /** This phone's Bluetooth name to the bike's ([bikeName]). */
    val onRename: () -> Unit,
    val onStartCarPlay: () -> Unit,
    val onDone: () -> Unit,
)

/**
 * The CarPlay tab with its view model. Every action that starts CarPlay (the switch, Start CarPlay) asks for CarPlay's
 * missing permissions, the microphone included, through the same runtime flow as the Permissions page, after a dialog
 * that says why ([CarPlayAccessEntry]); it starts the ride service once they are allowed, so a ride that had ended
 * picks CarPlay up while the app is on screen (a foreground service may only start then). Refused, the hero shows
 * Needs you with Allow while Android still asks, Open settings once it no longer does ([withAccess]).
 */
@Composable
fun CarPlayEntry(
    contentPadding: PaddingValues,
    navigation: CarPlayNavigation,
) {
    val viewModel: CarPlayViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val access = rememberCarPlayAccess { viewModel.recheckPermission() }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val startRide = { startRide(context, access, navigation.onExplainAccess) }
    val toggle = {
        val on = !state.enabled
        viewModel.setEnabled(on)
        if (on) startRide()
    }
    CarPlayScreen(
        state.copy(hero = state.hero.withAccess(access.status)),
        onAction = { action ->
            when (action) {
                TabAction.ADD_IPHONE -> navigation.onAddIphone()
                TabAction.CHOOSE_IPHONE -> navigation.onIphone()
                TabAction.TURN_ON_BETA -> toggle()
                TabAction.OPEN_CARPLAY -> navigation.onOpen()
                TabAction.START_CARPLAY -> viewModel.start().also { startRide() }
                TabAction.CANCEL, TabAction.DISCONNECT -> viewModel.stop()
                else -> fix(action, context, access) { enableBluetooth.launch(it) }
            }
        },
        onToggle = toggle,
        onIphone = { if (state.iphone.hasIphones) navigation.onIphone() else navigation.onAddIphone() },
        contentPadding = contentPadding,
        onScreen = navigation.onScreen,
    )
}

/** Starts the ride service when CarPlay's permissions allow, or explains and asks for them ([explain]). */
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

        // Refused for good: the hero shows Needs you with Open settings (withAccess).
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
            // ACTION_REQUEST_ENABLE needs BLUETOOTH_CONNECT, which CarPlay's permissions include.
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

/**
 * Pairing with the parts that need the Activity: Nearby devices (connect and advertise, asked before the first
 * "make visible"), Android's discoverable dialog (its result code is the granted seconds), and the ride service for
 * Start CarPlay.
 */
@Composable
private fun PairIphoneEntry(navigator: Navigator) {
    val viewModel: PairIphoneViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val discoverable =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // RESULT_CANCELED (0) when refused; otherwise the seconds Android granted.
            if (result.resultCode > 0) viewModel.visible(result.resultCode) else viewModel.notVisible()
        }
    val nearby =
        rememberRuntimeAccess(CarPlayPairing.PERMISSIONS) { granted ->
            viewModel.restart()
            if (granted) discoverable.launch(CarPlayPairing.discoverableIntent())
        }
    val carPlay = rememberCarPlayAccess()
    val bikeName = stringResource(R.string.bike_model)
    PairIphoneScreen(
        state,
        bikeName,
        PairIphoneActions(
            onBack = navigator::goBack,
            onMakeVisible = {
                if (nearby.status ==
                    Access.GRANTED
                ) {
                    discoverable.launch(CarPlayPairing.discoverableIntent())
                } else {
                    nearby.act()
                }
            },
            onAllow = nearby::act,
            onRename = { viewModel.rename(bikeName) },
            onStartCarPlay = {
                viewModel.startCarPlay()
                when (carPlay.status) {
                    Access.GRANTED -> RideService.start(context)
                    Access.ASK, Access.BLOCKED -> carPlay.act()
                }
                navigator.goBack()
            },
            onDone = navigator::goBack,
        ),
    )
}

@Composable
private fun IphoneSheetEntry(navigator: Navigator) {
    val viewModel: IphoneSheetViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    IphoneSheet(
        state,
        onChoose = { viewModel.choose(it.address) },
        onAdd = {
            // The sheet closes; pairing opens over the tab.
            navigator.goBack()
            navigator.navigate(PairIphoneRoute)
        },
        onForget = { askToForget(navigator, it) },
    )
}

@Composable
private fun ForgetEntry(
    route: ForgetIphoneRoute,
    navigator: Navigator,
) {
    val viewModel: IphoneSheetViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    HlDialog(
        HlIcons.Trash,
        stringResource(R.string.forget_iphone_title, route.name),
        stringResource(R.string.forget_iphone_detail, stringResource(R.string.bike_model)),
        confirm = stringResource(R.string.forget_iphone_confirm),
        onConfirm = { scope.launch { confirmForget(viewModel, navigator, route.address) } },
        dismiss = stringResource(R.string.cancel),
        onDismiss = navigator::goBack,
        destructive = true,
    )
}

/** The sheet's Forget: it only asks ([ForgetIphoneRoute]); nothing is forgotten until the rider confirms. */
internal fun askToForget(
    navigator: Navigator,
    iphone: PairedIphone,
) {
    navigator.navigate(ForgetIphoneRoute(iphone.address, iphone.name))
}

/** The dialog's Forget: the iPhone is forgotten, then the dialog and the sheet close, back on the tab. */
internal suspend fun confirmForget(
    viewModel: IphoneSheetViewModel,
    navigator: Navigator,
    address: String,
) {
    viewModel.forgetNow(address)
    navigator.goBackPast { it is IphoneSheetRoute }
}

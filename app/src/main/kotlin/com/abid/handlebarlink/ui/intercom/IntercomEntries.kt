package com.abid.handlebarlink.ui.intercom

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlDialog
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.ui.navigation.AddDeviceRoute
import com.abid.handlebarlink.ui.navigation.CallsRoute
import com.abid.handlebarlink.ui.navigation.DeviceSheetRoute
import com.abid.handlebarlink.ui.navigation.IntercomRoute
import com.abid.handlebarlink.ui.navigation.MusicRoute
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.RemoveDeviceRoute
import com.abid.handlebarlink.ui.navigation.sheet
import kotlinx.coroutines.launch

/**
 * The Intercom tab, its "Calls" and "Music" choosers (sheets) and its "Remove …?" confirmation (a dialog, as the
 * platform's dialog guidance has destructive confirmations). Each closes once its choice is stored (its own
 * scope ends with it).
 */
fun EntryProviderScope<NavKey>.intercomEntries(
    navigator: Navigator,
    contentPadding: PaddingValues,
) {
    entry<IntercomRoute> { IntercomEntry(navigator, contentPadding) }
    entry<CallsRoute>(metadata = sheet()) {
        val viewModel: IntercomViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        CallsSheet(
            state,
            onChoose = { route ->
                scope.launch {
                    viewModel.chooseCallsNow(route)
                    navigator.goBack()
                }
            },
            onCancel = navigator::goBack,
        )
    }
    entry<MusicRoute>(metadata = sheet()) {
        val viewModel: IntercomViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        MusicSheet(
            state.outputs,
            onChoose = { id ->
                viewModel.playOn(id)
                navigator.goBack()
            },
            onCancel = navigator::goBack,
        )
    }
    entry<RemoveDeviceRoute>(metadata = DialogSceneStrategy.dialog()) { route ->
        val viewModel: IntercomViewModel = hiltViewModel()
        val scope = rememberCoroutineScope()
        HlDialog(
            HlIcons.Trash,
            stringResource(R.string.intercom_remove_title, route.name),
            stringResource(R.string.intercom_remove_detail),
            confirm = stringResource(R.string.intercom_remove),
            onConfirm = {
                scope.launch {
                    viewModel.removeNow(route.address)
                    navigator.goBack()
                }
            },
            dismiss = stringResource(R.string.cancel),
            onDismiss = navigator::goBack,
            destructive = true,
        )
    }
}

@Composable
private fun IntercomEntry(
    navigator: Navigator,
    contentPadding: PaddingValues,
) {
    val viewModel: IntercomViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    IntercomScreen(
        state,
        IntercomActions(
            onAdd = { navigator.navigate(AddDeviceRoute) },
            onEdit = { device ->
                navigator.navigate(
                    DeviceSheetRoute(
                        device.address,
                        device.model,
                        device.name,
                        device.kind,
                        audio = true,
                        device.connected,
                    ),
                )
            },
            onChoosePrimary = viewModel::choosePrimary,
            onCalls = { navigator.navigate(CallsRoute) },
            onMusic = { navigator.navigate(MusicRoute) },
            onVendorApp = { viewModel.vendorApp()?.let(context::startActivity) },
            onRemove = { device -> navigator.navigate(RemoveDeviceRoute(device.address, device.name)) },
        ),
        contentPadding,
    )
}

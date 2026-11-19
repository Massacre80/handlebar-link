package com.abid.handlebarlink.ui.carplay

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.carPlayPermissions
import com.abid.handlebarlink.core.designsystem.component.HlDialog
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.ride.RideService
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.AccessState
import com.abid.handlebarlink.ui.permissions.rememberCarPlayAccess
import kotlinx.coroutines.launch

// The CarPlay tab's overlays for this step: the dialog that explains CarPlay's permissions before Android asks, and the
// CarPlay screen sheet.

/**
 * "CarPlay needs your OK": why CarPlay needs each permission still missing (the microphone: Siri and calls use it),
 * then Android's dialog on Continue (android-permissions-security §6: explain, then request). The answer re-reads
 * CarPlay's permissions and, when all are allowed, starts the ride service; then the dialog closes.
 */
@Composable
internal fun CarPlayAccessEntry(navigator: Navigator) {
    val viewModel: CarPlayViewModel = hiltViewModel()
    val context = LocalContext.current
    val access =
        rememberCarPlayAccess { granted ->
            viewModel.recheckPermission()
            if (granted) RideService.start(context)
            navigator.goBack()
        }
    val grants =
        remember {
            missingAccess(
                carPlayPermissions().filterNot {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                },
            )
        }
    val lines = grants.map { stringResource(it.ask) }
    HlDialog(
        HlIcons.CarPlay,
        stringResource(R.string.carplay_ask_title),
        lines.joinToString("\n"),
        confirm = stringResource(R.string.carplay_ask_continue),
        onConfirm = { continueAsking(context, access, navigator) },
        dismiss = stringResource(R.string.carplay_ask_not_now),
        onDismiss = navigator::goBack,
    )
}

/** The explanation's Continue: Android's dialog while it still asks; otherwise the settings page, or the ride. */
private fun continueAsking(
    context: Context,
    access: AccessState,
    navigator: Navigator,
) {
    when (access.status) {
        Access.ASK -> {
            access.act() // the answer closes the dialog
        }

        Access.BLOCKED -> {
            access.act()
            navigator.goBack()
        }

        Access.GRANTED -> {
            RideService.start(context)
            navigator.goBack()
        }
    }
}

/** The CarPlay screen sheet: the choice is stored, then the sheet closes; a running CarPlay reconnects with it. */
@Composable
internal fun CarPlayScreenEntry(navigator: Navigator) {
    val viewModel: CarPlayViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    CarPlayScreenSheet(
        state.orientation,
        onChoose = { orientation ->
            scope.launch {
                viewModel.chooseOrientation(orientation)
                navigator.goBack()
            }
        },
        onCancel = navigator::goBack,
    )
}

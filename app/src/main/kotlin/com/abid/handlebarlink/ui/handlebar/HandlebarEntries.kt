package com.abid.handlebarlink.ui.handlebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.core.designsystem.component.HlDialog
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.theme.HlMotion
import com.abid.handlebarlink.ui.navigation.ContainerPage
import com.abid.handlebarlink.ui.navigation.HandlebarPressRoute
import com.abid.handlebarlink.ui.navigation.HandlebarResetRoute
import com.abid.handlebarlink.ui.navigation.HandlebarRoute
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.containerPage
import com.abid.handlebarlink.ui.navigation.sheet
import kotlinx.coroutines.launch

/** Shared-bounds key: Home's Handlebar card and the Handlebar controls page. */
const val HANDLEBAR = "handlebar"

/**
 * Handlebar controls (grows out of Home's card), the chooser for one press in one situation (a sheet) and the
 * "Reset all buttons?" confirmation (a dialog, as Intercom's "Remove …?").
 */
fun EntryProviderScope<NavKey>.handlebarEntries(
    navigator: Navigator,
    motion: HlMotion,
) {
    entry<HandlebarRoute>(metadata = containerPage(motion)) {
        val viewModel: HandlebarViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        ContainerPage(HANDLEBAR) {
            HandlebarScreen(
                state,
                onSituation = viewModel::select,
                onPress = { navigator.navigate(HandlebarPressRoute(it.name, state.situation.name)) },
                onReset = { askToReset(navigator) },
                onBack = navigator::goBack,
            )
        }
    }
    entry<HandlebarPressRoute>(metadata = sheet()) { route ->
        val switch = HandlebarSwitch.entries.firstOrNull { it.name == route.switch } ?: HandlebarSwitch.UP
        val situation = CallSituation.entries.firstOrNull { it.name == route.situation } ?: CallSituation.RIDING
        val viewModel: HandlebarViewModel = hiltViewModel()
        // Both remembered: the first frame's state is worked out once, not on every recomposition.
        val first = remember(viewModel, situation, switch) { viewModel.pickerNow(situation, switch) }
        val picker by remember(viewModel, situation, switch) { viewModel.picker(situation, switch) }
            .collectAsStateWithLifecycle(first)
        val scope = rememberCoroutineScope()
        HandlebarPicker(
            picker,
            onChoose = { action ->
                scope.launch {
                    viewModel.assignNow(situation, switch, action)
                    navigator.goBack()
                }
            },
            onCancel = navigator::goBack,
        )
    }
    entry<HandlebarResetRoute>(metadata = DialogSceneStrategy.dialog()) {
        val viewModel: HandlebarViewModel = hiltViewModel()
        val scope = rememberCoroutineScope()
        HlDialog(
            HlIcons.Reset,
            stringResource(R.string.handlebar_reset_title),
            stringResource(R.string.handlebar_reset_detail),
            confirm = stringResource(R.string.handlebar_reset_confirm),
            onConfirm = { scope.launch { confirmReset(viewModel, navigator) } },
            dismiss = stringResource(R.string.cancel),
            onDismiss = navigator::goBack,
            destructive = true,
        )
    }
}

/** The page's Reset to default: it only asks ([HandlebarResetRoute]); nothing changes until the rider confirms. */
internal fun askToReset(navigator: Navigator) {
    navigator.navigate(HandlebarResetRoute)
}

/** The dialog's Reset: every situation back to its defaults, then the dialog closes. */
internal suspend fun confirmReset(
    viewModel: HandlebarViewModel,
    navigator: Navigator,
) {
    viewModel.resetNow()
    navigator.goBack()
}

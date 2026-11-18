package com.abid.handlebarlink.ui.handlebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.theme.HlMotion
import com.abid.handlebarlink.ui.controls.RiderActionSheet
import com.abid.handlebarlink.ui.navigation.ContainerPage
import com.abid.handlebarlink.ui.navigation.HandlebarPressRoute
import com.abid.handlebarlink.ui.navigation.HandlebarRoute
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.containerPage
import com.abid.handlebarlink.ui.navigation.sheet
import kotlinx.coroutines.launch

/** Shared-bounds key: Home's Handlebar card and the Handlebar controls page. */
const val HANDLEBAR = "handlebar"

/** Handlebar controls (grows out of Home's card) and the chooser for one press (a sheet). */
fun EntryProviderScope<NavKey>.handlebarEntries(
    navigator: Navigator,
    motion: HlMotion,
) {
    entry<HandlebarRoute>(metadata = containerPage(motion)) {
        val viewModel: HandlebarViewModel = hiltViewModel()
        val mapping by viewModel.current.collectAsStateWithLifecycle()
        ContainerPage(HANDLEBAR) {
            HandlebarScreen(
                mapping,
                onPress = { navigator.navigate(HandlebarPressRoute(it.name)) },
                onReset = viewModel::reset,
                onBack = navigator::goBack,
            )
        }
    }
    entry<HandlebarPressRoute>(metadata = sheet()) { route ->
        val switch = HandlebarSwitch.entries.firstOrNull { it.name == route.switch } ?: HandlebarSwitch.UP
        val viewModel: HandlebarViewModel = hiltViewModel()
        val mapping by viewModel.current.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        RiderActionSheet(
            SwitchKey.of(switch).icon,
            stringResource(pressName(switch)),
            stringResource(R.string.handlebar_sheet_detail),
            mapping[switch] ?: RiderAction.NOTHING,
            onChoose = { action ->
                scope.launch {
                    viewModel.assignNow(switch, action)
                    navigator.goBack()
                }
            },
            onCancel = navigator::goBack,
        )
    }
}

package com.abid.handlebarlink.ui.garage

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlDialog
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.garage.Colourway
import com.abid.handlebarlink.garage.GarageData
import com.abid.handlebarlink.garage.addCustom
import com.abid.handlebarlink.garage.chainDone
import com.abid.handlebarlink.garage.chooseColourway
import com.abid.handlebarlink.garage.enterOdometer
import com.abid.handlebarlink.garage.logService
import com.abid.handlebarlink.garage.removeCustom
import com.abid.handlebarlink.garage.setChainInterval
import com.abid.handlebarlink.garage.setPaper
import com.abid.handlebarlink.garage.setServiceInterval
import com.abid.handlebarlink.garage.updateCustom
import com.abid.handlebarlink.ui.navigation.GarageRoute
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.sheet
import kotlinx.coroutines.launch

/**
 * The Garage tab, its sheets (Log a service, odometer, service interval, chain, papers, own reminders, colourway), its
 * confirmations (dialogs, as the platform's dialog guidance has destructive ones) and the log page. A sheet closes once
 * its change is stored (its own scope ends with it); one that could not be stored stays open.
 */
fun EntryProviderScope<NavKey>.garageEntries(
    navigator: Navigator,
    contentPadding: PaddingValues,
) {
    entry<GarageRoute> { GarageEntry(navigator, contentPadding) }
    garageSheets(navigator)
    entry<DeleteReminderRoute>(metadata = DialogSceneStrategy.dialog()) { route -> DeleteEntry(route, navigator) }
    entry<ForgetBikeRoute>(metadata = DialogSceneStrategy.dialog()) { ForgetEntry(navigator) }
    entry<ServiceLogRoute> {
        val viewModel: GarageViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        ServiceLogScreen(state, onBack = navigator::goBack)
    }
}

/** The sheets that change the records, each closing once its change is stored ([Edit]). */
private fun EntryProviderScope<NavKey>.garageSheets(navigator: Navigator) {
    entry<LogServiceRoute>(metadata = sheet()) {
        Edit(navigator) { state, save ->
            LogServiceSheet(state.today, state.data.service, state.status.latest?.km, onSave = { input ->
                save { it.logService(input, nowMillis(), zone()) }
            }, onCancel = navigator::goBack)
        }
    }
    entry<OdometerRoute>(metadata = sheet()) {
        Edit(navigator) { state, save ->
            OdometerSheet(
                state.status.latest?.km,
                { km -> save { it.enterOdometer(km, nowMillis()) } },
                navigator::goBack,
            )
        }
    }
    entry<ServiceIntervalRoute>(metadata = sheet()) {
        Edit(navigator) { state, save ->
            ServiceIntervalSheet(state.data.service, { months, km ->
                save { it.setServiceInterval(months, km) }
            }, navigator::goBack)
        }
    }
    entry<ChainRoute>(metadata = sheet()) {
        Edit(navigator) { state, save ->
            ChainSheet(state.data.chain, state.status.latest?.km, { km, done ->
                save { data -> data.setChainInterval(km).let { if (done) it.chainDone() else it } }
            }, navigator::goBack)
        }
    }
    entry<PaperRoute>(metadata = sheet()) { route ->
        Edit(navigator) { state, save ->
            PaperSheet(route.kind, state.data.papers[route.kind], state.today, { day, lead ->
                save { it.setPaper(route.kind, day, lead) }
            }, navigator::goBack)
        }
    }
    entry<ReminderRoute>(metadata = sheet()) { route -> ReminderEntry(route, navigator) }
    entry<ColourwayRoute>(metadata = sheet()) {
        Edit(navigator) { state, save ->
            ColourwaySheet(Colourway.of(state.data.colourway), { colourway ->
                save { it.chooseColourway(colourway) }
            }, navigator::goBack)
        }
    }
}

@Composable
private fun GarageEntry(
    navigator: Navigator,
    contentPadding: PaddingValues,
) {
    val viewModel: GarageViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GarageScreen(
        state,
        GarageActions(
            onLogService = { navigator.navigate(LogServiceRoute) },
            onOdometer = { navigator.navigate(OdometerRoute) },
            onServiceInterval = { navigator.navigate(ServiceIntervalRoute) },
            onChain = { navigator.navigate(ChainRoute) },
            onPaper = { navigator.navigate(PaperRoute(it)) },
            onReminder = { navigator.navigate(ReminderRoute(it)) },
            onColourway = { navigator.navigate(ColourwayRoute) },
            onAllEntries = { navigator.navigate(ServiceLogRoute) },
            onForget = { navigator.navigate(ForgetBikeRoute) },
        ),
        contentPadding,
    )
}

/**
 * A sheet that changes the records: [content] gets the state and a save that stores the change, then closes the sheet
 * (it stays open when the change could not be written). Shows nothing until the records are read.
 */
@Composable
private fun Edit(
    navigator: Navigator,
    content: @Composable GarageViewModel.(GarageUiState, ((GarageData) -> GarageData) -> Unit) -> Unit,
) {
    val viewModel: GarageViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (!state.loaded) return
    viewModel.content(state) { change ->
        scope.launch { if (viewModel.save(change)) navigator.goBack() }
    }
}

@Composable
private fun ReminderEntry(
    route: ReminderRoute,
    navigator: Navigator,
) {
    Edit(navigator) { state, save ->
        val reminder = route.id?.let { id -> state.data.custom.firstOrNull { it.id == id } }
        ReminderSheet(
            reminder,
            state.today,
            onSave = { name, day, km ->
                save { data ->
                    if (reminder ==
                        null
                    ) {
                        data.addCustom(name, day, km)
                    } else {
                        data.updateCustom(reminder.id, name, day, km)
                    }
                }
            },
            onDelete = reminder?.let { { navigator.navigate(DeleteReminderRoute(it.id, it.name)) } },
            onCancel = navigator::goBack,
        )
    }
}

@Composable
private fun DeleteEntry(
    route: DeleteReminderRoute,
    navigator: Navigator,
) {
    val viewModel: GarageViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    HlDialog(
        HlIcons.Trash,
        stringResource(R.string.garage_delete_title, route.name),
        stringResource(R.string.garage_delete_text),
        confirm = stringResource(R.string.garage_delete),
        onConfirm = {
            scope.launch {
                // Back past the dialog and the reminder's sheet.
                if (viewModel.save { it.removeCustom(route.id) }) navigator.goBackPast { it is ReminderRoute }
            }
        },
        dismiss = stringResource(R.string.cancel),
        onDismiss = navigator::goBack,
        destructive = true,
    )
}

@Composable
private fun ForgetEntry(navigator: Navigator) {
    val viewModel: GarageViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    HlDialog(
        HlIcons.Trash,
        stringResource(R.string.garage_forget_title),
        stringResource(R.string.garage_forget_text),
        confirm = stringResource(R.string.garage_forget_confirm),
        onConfirm = {
            scope.launch {
                viewModel.forgetBike()
                navigator.goBack()
            }
        },
        dismiss = stringResource(R.string.cancel),
        onDismiss = navigator::goBack,
        destructive = true,
    )
}

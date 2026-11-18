package com.abid.handlebarlink.ui.controls

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.component.HlHeader
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlQuietButton
import com.abid.handlebarlink.core.designsystem.component.HlSheet
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * Choosing what a button does (a handlebar press, the intercom's assistant button): every [RiderAction] with
 * a line on what it does, the current one chosen; Siri is shown for our own CarPlay, not yet choosable.
 */
@Composable
fun RiderActionSheet(
    icon: Int,
    title: String,
    detail: String,
    chosen: RiderAction,
    onChoose: (RiderAction) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = HlTheme.spacing
    HlSheet(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(spacing.s20),
        ) {
            HlHeader(icon, title, detail)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                HlOptionRow(
                    null,
                    stringResource(R.string.assistant_siri),
                    stringResource(R.string.assistant_siri_detail),
                    selected = false,
                    onClick = {},
                    enabled = false,
                )
                RiderAction.entries.forEach { action ->
                    HlOptionRow(
                        null,
                        stringResource(actionName(action)),
                        stringResource(actionDetail(action)),
                        selected = chosen == action,
                        onClick = { onChoose(action) },
                    )
                }
            }
            HlQuietButton(stringResource(R.string.cancel), onCancel, Modifier.fillMaxWidth())
        }
    }
}

/** An action's name. */
@StringRes
fun actionName(action: RiderAction): Int =
    when (action) {
        RiderAction.PLAY_PAUSE -> R.string.assistant_play_pause
        RiderAction.NEXT -> R.string.assistant_next
        RiderAction.PREVIOUS -> R.string.assistant_previous
        RiderAction.BATTERY -> R.string.assistant_battery
        RiderAction.NOTHING -> R.string.assistant_nothing
    }

@StringRes
private fun actionDetail(action: RiderAction): Int =
    when (action) {
        RiderAction.PLAY_PAUSE -> R.string.assistant_play_pause_detail
        RiderAction.NEXT -> R.string.assistant_next_detail
        RiderAction.PREVIOUS -> R.string.assistant_previous_detail
        RiderAction.BATTERY -> R.string.assistant_battery_detail
        RiderAction.NOTHING -> R.string.assistant_nothing_detail
    }

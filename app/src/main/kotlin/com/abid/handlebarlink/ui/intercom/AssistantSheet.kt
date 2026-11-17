package com.abid.handlebarlink.ui.intercom

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
import com.abid.handlebarlink.core.designsystem.component.HlHeader
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlQuietButton
import com.abid.handlebarlink.core.designsystem.component.HlSheet
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.devices.AssistantAction

/**
 * "Assistant button": what the intercom's assistant button does. Siri comes with our own CarPlay receiver:
 * shown, not yet choosable.
 */
@Composable
fun AssistantSheet(
    chosen: AssistantAction,
    onChoose: (AssistantAction) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = HlTheme.spacing
    HlSheet(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(spacing.s20),
        ) {
            HlHeader(
                HlIcons.Intercom,
                stringResource(R.string.assistant_button),
                stringResource(R.string.assistant_detail),
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                HlOptionRow(
                    null,
                    stringResource(R.string.assistant_siri),
                    stringResource(R.string.assistant_siri_detail),
                    selected = false,
                    onClick = {},
                    enabled = false,
                )
                AssistantAction.entries.forEach { action ->
                    HlOptionRow(
                        null,
                        stringResource(assistantName(action)),
                        stringResource(assistantDetail(action)),
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
fun assistantName(action: AssistantAction): Int =
    when (action) {
        AssistantAction.PLAY_PAUSE -> R.string.assistant_play_pause
        AssistantAction.NEXT -> R.string.assistant_next
        AssistantAction.BATTERY -> R.string.assistant_battery
        AssistantAction.NOTHING -> R.string.assistant_nothing
    }

@StringRes
private fun assistantDetail(action: AssistantAction): Int =
    when (action) {
        AssistantAction.PLAY_PAUSE -> R.string.assistant_play_pause_detail
        AssistantAction.NEXT -> R.string.assistant_next_detail
        AssistantAction.BATTERY -> R.string.assistant_battery_detail
        AssistantAction.NOTHING -> R.string.assistant_nothing_detail
    }

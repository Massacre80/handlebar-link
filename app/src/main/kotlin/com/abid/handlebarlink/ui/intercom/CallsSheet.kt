package com.abid.handlebarlink.ui.intercom

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
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlSheet
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.devices.PHONE
import com.abid.handlebarlink.ui.devices.kindIcon

/**
 * "Calls": where call audio goes, chosen in the app. Each added device (one not connected for calls says so;
 * calls reach it once it connects) and the phone itself. The app's call audio follows the choice through
 * Telecom's endpoints.
 */
@Composable
fun CallsSheet(
    state: IntercomUiState,
    onChoose: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = HlTheme.spacing
    HlSheet(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
        ) {
            HlHeader(
                HlIcons.Intercom,
                stringResource(R.string.intercom_calls),
                stringResource(R.string.intercom_calls_detail),
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                state.devices.forEach { device ->
                    HlOptionRow(
                        kindIcon(device.kind),
                        device.name,
                        stringResource(if (device.calls) R.string.device_connected else R.string.intercom_off),
                        selected = (state.calls as? Calls.Device)?.address == device.address,
                        onClick = { onChoose(device.address) },
                    )
                }
                HlOptionRow(
                    HlIcons.Phone,
                    stringResource(R.string.intercom_phone),
                    stringResource(R.string.intercom_phone_detail),
                    selected = state.calls == Calls.Phone,
                    onClick = { onChoose(PHONE) },
                )
            }
            HlSecondaryButton(stringResource(R.string.cancel), onCancel)
        }
    }
}

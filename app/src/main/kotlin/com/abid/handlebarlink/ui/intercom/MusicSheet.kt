package com.abid.handlebarlink.ui.intercom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
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
 * "Music": where music (CarPlay's media) plays, chosen in the app. Each added device (one not connected for music says
 * so; music reaches it once it connects) and the phone itself. Our CarPlay plays media on the chosen device with
 * `setPreferredDevice`, so the device shows here even while it is disconnected.
 */
@Composable
fun MusicSheet(
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
                HlIcons.Earbuds,
                stringResource(R.string.intercom_music),
                stringResource(R.string.intercom_music_detail),
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                state.devices.forEach { device ->
                    HlOptionRow(
                        kindIcon(device.kind),
                        device.name,
                        stringResource(if (device.music) R.string.device_connected else R.string.intercom_off),
                        selected = (state.musicChoice as? Calls.Device)?.address == device.address,
                        onClick = { onChoose(device.address) },
                    )
                }
                HlOptionRow(
                    HlIcons.Phone,
                    stringResource(R.string.intercom_phone),
                    stringResource(R.string.intercom_phone_detail),
                    selected = state.musicChoice == Calls.Phone,
                    onClick = { onChoose(PHONE) },
                )
            }
            HlSecondaryButton(stringResource(R.string.cancel), onCancel)
        }
    }
}

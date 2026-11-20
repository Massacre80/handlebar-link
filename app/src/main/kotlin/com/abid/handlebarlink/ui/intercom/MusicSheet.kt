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
import com.abid.handlebarlink.ui.devices.kindIcon

/**
 * "Music": where media plays, chosen in the app (Android 14+, MediaRouter2): every connected output the
 * system offers, an added device under the rider's name for it. Choosing moves all media, CarPlay's too.
 */
@Composable
fun MusicSheet(
    outputs: List<Output>,
    onChoose: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = HlTheme.spacing
    HlSheet(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(spacing.s20),
        ) {
            HlHeader(
                HlIcons.Earbuds,
                stringResource(R.string.intercom_music),
                stringResource(R.string.intercom_music_detail),
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                outputs.forEach { output ->
                    HlOptionRow(
                        when {
                            output.kind != null -> kindIcon(output.kind)
                            output.bluetooth -> HlIcons.Bluetooth
                            else -> HlIcons.Phone
                        },
                        // The phone is "This phone" here as in Calls; a device carries the rider's name for it.
                        if (output.bluetooth) output.name else stringResource(R.string.intercom_phone),
                        stringResource(
                            when {
                                output.selected -> R.string.intercom_output_playing
                                output.bluetooth -> R.string.intercom_output_connected
                                else -> R.string.intercom_output_speaker
                            },
                        ),
                        selected = output.selected,
                        onClick = { onChoose(output.id) },
                    )
                }
            }
            HlSecondaryButton(stringResource(R.string.cancel), onCancel)
        }
    }
}

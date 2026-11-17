package com.abid.handlebarlink.ui.devices

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlHeader
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlQuietButton
import com.abid.handlebarlink.core.designsystem.component.HlSectionLabel
import com.abid.handlebarlink.core.designsystem.component.HlSheet
import com.abid.handlebarlink.core.designsystem.component.HlTextField
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.devices.DeviceKind

/**
 * "What is it?" (Paper "Flow · Add device"): the device, its kind (the suggestion pre-chosen), its name on
 * Home, Save and Cancel. Anchored to the bottom over a scrim that closes it; it rises above the keyboard and
 * scrolls when it does not fit.
 */
@Composable
fun DeviceSheet(
    device: SheetDevice,
    kind: DeviceKind,
    onKind: (DeviceKind) -> Unit,
    name: TextFieldState,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = HlTheme.spacing
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The scrim above the sheet: a tap there is Cancel (the dialog's own outside tap cannot reach it).
        Spacer(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onCancel),
        )
        HlSheet(
            Modifier
                .widthIn(max = HlTheme.sizes.contentMax)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                verticalArrangement = Arrangement.spacedBy(spacing.s20),
            ) {
                val now = stringResource(if (device.connected) R.string.device_connected else R.string.device_paired)
                HlHeader(
                    HlIcons.Bluetooth,
                    device.name,
                    if (device.audio) stringResource(R.string.device_looks_audio, now) else now,
                )
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                    HlSectionLabel(stringResource(R.string.device_sheet_kind))
                    KINDS.forEach { (option, title, detail) ->
                        HlOptionRow(
                            kindIcon(option),
                            stringResource(title),
                            stringResource(detail),
                            selected = kind == option,
                            onClick = { onKind(option) },
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                    HlSectionLabel(stringResource(R.string.device_sheet_name))
                    HlTextField(name, stringResource(R.string.device_sheet_name_hint), placeholder = device.name)
                }
                Column(Modifier.padding(top = spacing.s4), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                    HlButton(stringResource(R.string.device_save), onSave, Modifier.fillMaxWidth())
                    HlQuietButton(stringResource(R.string.cancel), onCancel, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private data class KindOption(
    val kind: DeviceKind,
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
)

private val KINDS =
    listOf(
        KindOption(DeviceKind.INTERCOM, R.string.device_kind_intercom, R.string.device_kind_intercom_detail),
        KindOption(DeviceKind.HEADSET, R.string.device_kind_headset, R.string.device_kind_headset_detail),
        KindOption(DeviceKind.OTHER, R.string.device_kind_other, R.string.device_kind_other_detail),
    )

@Composable
private fun SheetPreview(variant: HlThemeVariant) {
    HlTheme(variant) {
        DeviceSheet(
            SheetDevice("EJEAS V6 Pro+", audio = true, connected = true),
            DeviceKind.INTERCOM,
            onKind = {},
            name = rememberTextFieldState("Helmet intercom"),
            onSave = {},
            onCancel = {},
        )
    }
}

@Preview(
    name = "What is it? · Night",
    widthDp = 411,
    heightDp = 856,
    backgroundColor = 0xFF7C7E78,
    showBackground = true,
)
@Composable
private fun SheetNightPreview() = SheetPreview(HlThemeVariant.NIGHT)

@Preview(
    name = "What is it? · Bento",
    widthDp = 411,
    heightDp = 856,
    backgroundColor = 0xFF7C7E78,
    showBackground = true,
)
@Composable
private fun SheetBentoPreview() = SheetPreview(HlThemeVariant.BENTO)

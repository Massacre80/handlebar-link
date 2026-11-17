package com.abid.handlebarlink.ui.intercom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.DeviceTile
import com.abid.handlebarlink.core.designsystem.component.HlActionRow
import com.abid.handlebarlink.core.designsystem.component.HlBatteryLevel
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlCardAction
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlSection
import com.abid.handlebarlink.core.designsystem.component.HlSectionLabel
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.devices.ADD_DEVICE
import com.abid.handlebarlink.ui.devices.kindIcon
import com.abid.handlebarlink.ui.devices.kindName
import com.abid.handlebarlink.ui.navigation.containerContent
import com.abid.handlebarlink.ui.navigation.containerTransform
import com.abid.handlebarlink.ui.navigation.rememberContainerTransform

/**
 * The Intercom tab (research of 2026-11-18): the primary device as on Home; where calls and music play
 * (Android's public routing) and how to change it; whether the intercom's play, pause and skip reach
 * CarLink; the primary among several; its details, the maker's app and removal. Nothing added: how to add.
 */
@Composable
fun IntercomScreen(
    state: IntercomUiState,
    actions: IntercomActions,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(contentPadding)
            .wrapContentWidth()
            .widthIn(max = HlTheme.sizes.contentMax)
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s24),
    ) {
        HlText(
            stringResource(R.string.tab_devices),
            HlTheme.typography.title,
            HlTheme.colors.ink,
            Modifier.semantics { heading() },
        )
        val primary = state.primary
        if (primary == null) {
            Empty(actions.onAdd)
        } else {
            PrimaryCard(primary, state.reconnect, actions.onReconnect)
            Audio(state, primary, actions)
            Buttons(state.buttons)
            if (state.devices.size > 1) Primary(state.devices, actions.onChoosePrimary)
            Details(primary, state.vendorApp, actions)
        }
    }
}

@Composable
private fun Empty(onAdd: () -> Unit) {
    val transform = rememberContainerTransform(ADD_DEVICE, expanded = false)
    Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12)) {
        HlText(stringResource(R.string.intercom_none), HlTheme.typography.lead, HlTheme.colors.ink)
        HlText(stringResource(R.string.intercom_empty_detail), HlTheme.typography.body, HlTheme.colors.inkMuted)
        // Grows into Add device, as Home's tile does.
        HlButton(
            stringResource(R.string.add_device),
            onAdd,
            Modifier.fillMaxWidth().containerTransform(transform).containerContent(transform),
            chevron = true,
        )
    }
}

/**
 * The device as on Home. Disconnected, its card carries the white "Connect" (Android 17's connect, after one
 * companion-link consent), "Connecting…" while Android connects, and why not in the card's line if it cannot.
 */
@Composable
private fun PrimaryCard(
    device: IntercomDevice,
    reconnect: Reconnecting,
    onReconnect: (String) -> Unit,
) {
    val connecting = reconnect is Reconnecting.Connecting || reconnect is Reconnecting.Linking
    val problem =
        when (reconnect) {
            Reconnecting.Unsupported -> R.string.intercom_reconnect_unsupported
            Reconnecting.Failed -> R.string.intercom_reconnect_failed
            else -> null
        }
    val detail =
        when {
            problem != null && !device.connected -> stringResource(problem)
            !device.connected -> stringResource(R.string.device_not_connected)
            device.model != device.name -> device.model
            else -> stringResource(R.string.device_connected)
        }
    DeviceTile(
        stringResource(kindName(device.kind)),
        device.name,
        detail,
        connected = device.connected,
        modifier = Modifier.fillMaxWidth(),
        battery = device.battery?.let { HlBatteryLevel(it, stringResource(R.string.device_battery, it)) },
        icon = kindIcon(device.kind),
        action =
            if (device.connected) {
                null
            } else {
                HlCardAction(
                    stringResource(if (connecting) R.string.intercom_reconnecting else R.string.intercom_connect),
                    onClick = { onReconnect(device.address) },
                    enabled = !connecting,
                )
            },
        detailColor = if (problem != null && !device.connected) HlTheme.colors.danger else Color.Unspecified,
    )
}

@Composable
private fun Audio(
    state: IntercomUiState,
    device: IntercomDevice,
    actions: IntercomActions,
) {
    // Calls and Music name where audio goes the same way: the device as the rider named it, or this phone.
    val music =
        when (val on = state.music) {
            Music.Primary -> device.name
            is Music.Other -> on.name
            Music.OtherBluetooth -> stringResource(R.string.intercom_other_bluetooth)
            Music.Phone -> stringResource(R.string.intercom_phone)
            Music.Unknown -> if (device.music) device.name else stringResource(R.string.intercom_off)
        }
    val calls =
        when (val to = state.calls) {
            Calls.Phone -> stringResource(R.string.intercom_phone)
            is Calls.Device -> to.name
        }
    HlSection(stringResource(R.string.intercom_audio)) {
        // Where calls go is chosen here, in the app (Telecom endpoints): the row opens the chooser.
        HlValueRow(
            stringResource(R.string.intercom_calls),
            hint = calls,
            onClick = actions.onCalls,
            container = Color.Transparent,
            chevron = true,
        )
        HlGroupDivider()
        // Chosen here too where Android allows it (14+: MediaRouter2); otherwise where it plays, as status.
        HlValueRow(
            stringResource(R.string.intercom_music),
            hint = music,
            onClick = actions.onMusic.takeIf { state.outputs.isNotEmpty() },
            container = Color.Transparent,
            chevron = state.outputs.isNotEmpty(),
        )
    }
}

@Composable
private fun Buttons(buttons: Buttons) {
    val colors = HlTheme.colors
    val (text, color) =
        when (buttons) {
            Buttons.CARLINK -> stringResource(R.string.intercom_buttons_carlink) to colors.success
            Buttons.OTHER_APP -> stringResource(R.string.intercom_buttons_other) to colors.danger
            Buttons.UNKNOWN -> stringResource(R.string.intercom_buttons_unknown) to colors.inkMuted
        }
    HlSection(stringResource(R.string.intercom_buttons)) {
        Column(
            Modifier.padding(horizontal = HlTheme.spacing.s16, vertical = HlTheme.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s2),
        ) {
            HlText(stringResource(R.string.intercom_buttons_media), HlTheme.typography.value, colors.ink)
            HlText(text, HlTheme.typography.meta, color)
        }
    }
}

@Composable
private fun Primary(
    devices: List<IntercomDevice>,
    onChoose: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        HlSectionLabel(stringResource(R.string.intercom_primary))
        HlText(
            stringResource(R.string.intercom_primary_intro),
            HlTheme.typography.meta,
            HlTheme.colors.inkMuted,
            Modifier.padding(horizontal = HlTheme.spacing.s4),
        )
        HlGroup {
            Column(Modifier.selectableGroup()) {
                devices.forEachIndexed { index, device ->
                    // No hairline beside the chosen (filled) row; the primary is first.
                    if (index > 1) HlGroupDivider()
                    HlOptionRow(
                        kindIcon(device.kind),
                        device.name,
                        stringResource(if (device.connected) R.string.device_connected else R.string.intercom_off),
                        selected = index == 0,
                        onClick = { onChoose(device.address) },
                        container = Color.Transparent,
                    )
                }
            }
        }
    }
}

@Composable
private fun Details(
    device: IntercomDevice,
    vendorApp: Boolean,
    actions: IntercomActions,
) {
    HlSection(stringResource(R.string.intercom_details)) {
        HlValueRow(
            stringResource(R.string.intercom_name),
            hint = device.name,
            onClick = { actions.onEdit(device) },
            container = Color.Transparent,
            chevron = true,
        )
        HlGroupDivider()
        HlValueRow(
            stringResource(R.string.intercom_kind),
            hint = stringResource(kindName(device.kind)),
            onClick = { actions.onEdit(device) },
            container = Color.Transparent,
            chevron = true,
        )
        HlGroupDivider()
        HlValueRow(stringResource(R.string.intercom_model), hint = device.model, container = Color.Transparent)
        if (vendorApp) {
            HlGroupDivider()
            HlValueRow(
                stringResource(R.string.intercom_vendor_app),
                hint = null,
                onClick = actions.onVendorApp,
                container = Color.Transparent,
                chevron = true,
            )
        }
        HlGroupDivider()
        // Destructive: red with its icon, so it reads as a removal before it is tapped; a dialog confirms.
        HlActionRow(
            HlIcons.Trash,
            stringResource(R.string.intercom_remove),
            { actions.onRemove(device) },
            destructive = true,
        )
    }
}

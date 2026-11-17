package com.abid.handlebarlink.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlNavRow
import com.abid.handlebarlink.core.designsystem.component.HlSection
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.ui.navigation.containerContent
import com.abid.handlebarlink.ui.navigation.containerTransform
import com.abid.handlebarlink.ui.navigation.rememberContainerTransform
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.PermissionItem
import com.abid.handlebarlink.ui.permissions.PermissionList
import com.abid.handlebarlink.ui.settings.SubPage

/**
 * Add device: the phone's paired devices, audio first (connected on top), others folded away; "Pair a new
 * device" always last. Without Nearby devices or with Bluetooth off, the way out comes first, as a row with
 * its pill (the permission list's look).
 */
@Composable
fun AddDeviceScreen(
    state: AddDeviceUiState,
    nearby: Access,
    actions: AddDeviceActions,
) {
    SubPage(stringResource(R.string.add_device), actions.onBack) {
        HlText(stringResource(R.string.devices_intro), HlTheme.typography.body, HlTheme.colors.inkMuted)
        when (state) {
            AddDeviceUiState.Loading -> {}

            AddDeviceUiState.NotAllowed -> {
                PermissionList(
                    listOf(
                        PermissionItem(
                            R.string.permission_nearby,
                            R.string.devices_nearby_why,
                            nearby,
                            actions.onAllow,
                        ),
                    ),
                )
            }

            AddDeviceUiState.BluetoothOff -> {
                PermissionList(
                    listOf(
                        PermissionItem(
                            R.string.devices_bluetooth_off,
                            R.string.devices_bluetooth_off_why,
                            Access.ASK,
                            actions.onTurnOn,
                            action = R.string.setup_fix_open,
                        ),
                    ),
                )
            }

            is AddDeviceUiState.Ready -> {
                DeviceLists(state, actions.onPick)
            }
        }
        PairNew(
            empty = state is AddDeviceUiState.Ready && state.audio.isEmpty() && state.other.isEmpty(),
            actions.onPair,
        )
    }
}

@Composable
private fun DeviceLists(
    state: AddDeviceUiState.Ready,
    onPick: (DeviceChoice) -> Unit,
) {
    if (state.audio.isNotEmpty()) {
        HlSection(stringResource(R.string.devices_audio)) { Devices(state.audio, onPick) }
    }
    if (state.other.isNotEmpty()) {
        // Watches, cars and computers rarely belong here: folded, still reachable. Open when nothing else is.
        var open by rememberSaveable { mutableStateOf(state.audio.isEmpty()) }
        HlSection(stringResource(R.string.devices_other)) {
            // The list unfolds while the "show" row folds away: one smooth change of height.
            val motion = HlTheme.motion
            AnimatedVisibility(open, enter = unfold(motion), exit = fold(motion)) { Devices(state.other, onPick) }
            AnimatedVisibility(!open, enter = unfold(motion), exit = fold(motion)) {
                HlNavRow(
                    HlIcons.Bluetooth,
                    stringResource(R.string.devices_other_show),
                    detail = state.other.joinToString { it.name },
                    onClick = { open = true },
                )
            }
        }
    }
}

@Composable
private fun Devices(
    devices: List<DeviceChoice>,
    onPick: (DeviceChoice) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        devices.forEachIndexed { index, device ->
            if (index > 0) HlGroupDivider()
            HlNavRow(
                kindIcon(device.kind),
                device.name,
                detail = stateText(device),
                onClick = { onPick(device) },
                detailColor = if (device.connected) HlTheme.colors.success else Color.Unspecified,
            )
        }
    }
}

/** "Pair a new device": with what to do first when nothing is paired, else the last row on its own. */
@Composable
private fun PairNew(
    empty: Boolean,
    onPair: () -> Unit,
) {
    val transform = rememberContainerTransform(PAIR_DEVICE)
    val row: @Composable () -> Unit = {
        // Grows into Pair a new device; the row keeps its own size inside.
        Box(Modifier.containerTransform(transform)) {
            HlNavRow(
                HlIcons.Plus,
                stringResource(R.string.devices_pair_new),
                detail = stringResource(R.string.devices_pair_new_detail),
                onClick = onPair,
                modifier = Modifier.containerContent(transform),
            )
        }
    }
    if (empty) {
        HlSection(stringResource(R.string.devices_none)) {
            HlText(
                stringResource(R.string.devices_none_detail),
                HlTheme.typography.meta,
                HlTheme.colors.inkMuted,
                Modifier.padding(HlTheme.spacing.s16),
            )
            HlGroupDivider()
            row()
        }
    } else {
        HlGroup { row() }
    }
}

@Composable
private fun stateText(device: DeviceChoice): String {
    val now = stringResource(if (device.connected) R.string.device_connected else R.string.device_paired)
    return if (device.added) {
        stringResource(
            R.string.device_state_both,
            stringResource(R.string.device_added),
            now,
        )
    } else {
        now
    }
}

private val PreviewDevices =
    AddDeviceUiState.Ready(
        audio =
            listOf(
                DeviceChoice("AA", "EJEAS V6 Pro+", "EJEAS V6 Pro+", true, false, DeviceKind.INTERCOM, audio = true),
                DeviceChoice("BB", "Buds", "Galaxy Buds2", false, true, DeviceKind.HEADSET, audio = true),
            ),
        other =
            listOf(
                DeviceChoice("CC", "Galaxy Watch", "Galaxy Watch", false, false, DeviceKind.OTHER, audio = false),
            ),
    )

private val NoActions = AddDeviceActions({}, {}, {}, {}, {})

@Preview(name = "Add device · Night", widthDp = 411, heightDp = 856)
@Composable
private fun AddDeviceNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { AddDeviceScreen(PreviewDevices, Access.GRANTED, NoActions) }
    }
}

@Preview(name = "Add device · Bento", widthDp = 411, heightDp = 856)
@Composable
private fun AddDeviceBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) { AddDeviceScreen(PreviewDevices, Access.GRANTED, NoActions) }
    }
}

@Preview(name = "Add device · Bluetooth off", widthDp = 411, heightDp = 600)
@Composable
private fun AddDeviceOffPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(
            Modifier.fillMaxSize(),
        ) { AddDeviceScreen(AddDeviceUiState.BluetoothOff, Access.GRANTED, NoActions) }
    }
}

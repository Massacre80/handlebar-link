package com.abid.handlebarlink.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlActionPill
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlNavRow
import com.abid.handlebarlink.core.designsystem.component.HlSection
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.pressScale
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.devices.NearbyDevice
import com.abid.handlebarlink.devices.PairState
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.PermissionItem
import com.abid.handlebarlink.ui.permissions.PermissionList
import com.abid.handlebarlink.ui.settings.SubPage
import com.abid.handlebarlink.ui.setup.SearchSpinner
import com.abid.handlebarlink.ui.setup.SearchState

/**
 * Pair a new device, inside the app: the search (setup's spinner), the devices in pairing mode (audio first,
 * others folded), each row its own pairing state; "Not listed?" opens Android's Bluetooth settings for a
 * device that needs a typed PIN.
 */
@Composable
fun PairDeviceScreen(
    state: PairDeviceUiState,
    nearby: Access,
    actions: PairDeviceActions,
) {
    SubPage(stringResource(R.string.devices_pair_new), actions.onBack) {
        HlText(stringResource(R.string.pair_intro), HlTheme.typography.body, HlTheme.colors.inkMuted)
        when (state) {
            PairDeviceUiState.Loading -> {}

            PairDeviceUiState.NotAllowed -> {
                PermissionList(
                    listOf(
                        PermissionItem(R.string.permission_nearby, R.string.pair_nearby_why, nearby, actions.onAllow),
                    ),
                )
            }

            PairDeviceUiState.BluetoothOff -> {
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

            is PairDeviceUiState.Nearby -> {
                Search(state.searching, actions.onSearchAgain)
                Found(state, actions.onPair)
            }
        }
        HlGroup {
            HlNavRow(
                HlIcons.Bluetooth,
                stringResource(R.string.pair_not_listed),
                detail = stringResource(R.string.pair_not_listed_detail),
                onClick = actions.onNotListed,
            )
        }
    }
}

/** The search: spinning while it runs; once it ends, the row is "Search again". */
@Composable
private fun Search(
    searching: Boolean,
    onSearchAgain: () -> Unit,
) {
    val spacing = HlTheme.spacing
    val press = remember { MutableInteractionSource() }
    HlGroup {
        Row(
            Modifier
                .pressScale(press, HlTheme.motion)
                .fillMaxWidth()
                .then(
                    if (searching) {
                        Modifier
                    } else {
                        Modifier.clickable(press, LocalIndication.current, role = Role.Button, onClick = onSearchAgain)
                    },
                ).padding(start = spacing.s12, end = spacing.s14, top = spacing.s12, bottom = spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            SearchSpinner(if (searching) SearchState.SEARCHING else SearchState.ENDED)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                HlText(
                    stringResource(if (searching) R.string.pair_searching else R.string.pair_ended),
                    HlTheme.typography.value,
                    HlTheme.colors.ink,
                )
                HlText(
                    stringResource(if (searching) R.string.pair_searching_detail else R.string.pair_ended_detail),
                    HlTheme.typography.meta,
                    HlTheme.colors.inkMuted,
                )
            }
            if (!searching) HlActionPill(stringResource(R.string.pair_again))
        }
    }
}

@Composable
private fun Found(
    state: PairDeviceUiState.Nearby,
    onPair: (NearbyDevice) -> Unit,
) {
    if (state.audio.isNotEmpty()) {
        HlSection(stringResource(R.string.pair_ready)) { Devices(state.audio, state.attempt, onPair) }
    }
    if (state.other.isNotEmpty()) {
        var open by rememberSaveable { mutableStateOf(state.audio.isEmpty()) }
        HlSection(stringResource(R.string.devices_other)) {
            AnimatedVisibility(open) { Devices(state.other, state.attempt, onPair) }
            if (!open) {
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
    devices: List<NearbyDevice>,
    attempt: PairAttempt?,
    onPair: (NearbyDevice) -> Unit,
) {
    Column {
        devices.forEachIndexed { index, device ->
            if (index > 0) HlGroupDivider()
            val state = attempt?.takeIf { it.device.address == device.address }?.state
            HlNavRow(
                kindIcon(device.suggested),
                device.name,
                detail =
                    when (state) {
                        PairState.PAIRING, PairState.PAIRED -> stringResource(R.string.pair_pairing)
                        PairState.FAILED -> stringResource(R.string.pair_failed)
                        null -> null
                    },
                onClick = { onPair(device) },
                detailColor = if (state == PairState.FAILED) HlTheme.colors.danger else Color.Unspecified,
            )
        }
    }
}

private val PreviewNearby =
    PairDeviceUiState.Nearby(
        audio =
            listOf(
                NearbyDevice("AA", "EJEAS V6 Pro+", audio = true, suggested = DeviceKind.INTERCOM),
                NearbyDevice("BB", "Galaxy Buds2", audio = true, suggested = DeviceKind.HEADSET),
            ),
        other = listOf(NearbyDevice("CC", "Living room TV", audio = false, suggested = DeviceKind.OTHER)),
        searching = true,
        attempt =
            PairAttempt(
                NearbyDevice("BB", "Galaxy Buds2", audio = true, suggested = DeviceKind.HEADSET),
                PairState.FAILED,
            ),
    )

private val NoActions = PairDeviceActions({}, {}, {}, {}, {}, {})

@Preview(name = "Pair · Night", widthDp = 411, heightDp = 856)
@Composable
private fun PairNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { PairDeviceScreen(PreviewNearby, Access.GRANTED, NoActions) }
    }
}

@Preview(name = "Pair · Bento", widthDp = 411, heightDp = 856)
@Composable
private fun PairBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) {
            PairDeviceScreen(PreviewNearby.copy(searching = false), Access.GRANTED, NoActions)
        }
    }
}

package com.abid.handlebarlink.ui.intercom

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.devices.AssistantHandler
import com.abid.handlebarlink.devices.DeviceKind

private val PreviewDevice =
    IntercomDevice(
        address = "AA",
        name = "V6 Pro+",
        model = "V6 Pro+",
        kind = DeviceKind.INTERCOM,
        connected = true,
        calls = true,
        music = true,
        battery = PREVIEW_BATTERY,
    )

/** One intercom connected, its battery known, the assistant button set; a second, passenger's intercom. */
internal val PreviewIntercom =
    IntercomUiState(
        devices = listOf(PreviewDevice, PreviewDevice.copy(address = "BB", name = "Passenger", connected = false)),
        music = Music.Primary,
        handler = AssistantHandler.ThisApp,
        calls = Calls.Device("AA", "V6 Pro+"),
        outputs =
            listOf(
                Output("bt", "V6 Pro+", DeviceKind.INTERCOM, bluetooth = true, selected = true),
                Output("phone", "Phone speaker", null, bluetooth = false, selected = false),
            ),
        vendorApp = true,
    )

internal val NoIntercomActions = IntercomActions({}, {}, {}, {}, {}, {}, {}, {}, {})

private const val PREVIEW_BATTERY = 50

@Preview(name = "Intercom · Night", widthDp = 411, heightDp = 1400)
@Composable
private fun IntercomNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { IntercomScreen(PreviewIntercom, NoIntercomActions, PaddingValues()) }
    }
}

@Preview(name = "Intercom · Bento", widthDp = 411, heightDp = 1400)
@Composable
private fun IntercomBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) {
            IntercomScreen(
                PreviewIntercom.copy(handler = AssistantHandler.Other("Google")),
                NoIntercomActions,
                PaddingValues(),
            )
        }
    }
}

@Preview(name = "Intercom · none", widthDp = 411, heightDp = 600)
@Composable
private fun IntercomEmptyPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { IntercomScreen(IntercomUiState(), NoIntercomActions, PaddingValues()) }
    }
}

@Preview(name = "Devices · disconnected", widthDp = 411, heightDp = 420)
@Composable
private fun DisconnectedPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) {
            IntercomScreen(
                PreviewIntercom.copy(
                    devices =
                        PreviewIntercom.devices.map {
                            it.copy(
                                connected = false,
                                calls = false,
                                music = false,
                                battery = null,
                            )
                        },
                ),
                NoIntercomActions,
                PaddingValues(),
            )
        }
    }
}

@Preview(name = "Devices · could not connect", widthDp = 411, heightDp = 420)
@Composable
private fun FailedPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) {
            IntercomScreen(
                PreviewIntercom.copy(
                    devices =
                        PreviewIntercom.devices.map {
                            it.copy(
                                connected = false,
                                calls = false,
                                music = false,
                                battery = null,
                            )
                        },
                    reconnect = Reconnecting.Failed,
                ),
                NoIntercomActions,
                PaddingValues(),
            )
        }
    }
}

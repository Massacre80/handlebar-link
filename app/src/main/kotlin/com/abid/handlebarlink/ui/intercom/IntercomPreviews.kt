package com.abid.handlebarlink.ui.intercom

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlTab
import com.abid.handlebarlink.core.designsystem.component.HlTabBar
import com.abid.handlebarlink.core.designsystem.component.HlTabRail
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.devices.AssistantHandler
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.navigation.IntercomRoute
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import com.abid.handlebarlink.ui.navigation.tabIndex

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

/**
 * Nothing added (Paper "Devices 1 — No intercom yet"), framed as in the app: over the tab bar upright, beside the rail
 * on its side ([WindowLayout.PANES], at the Poco's landscape size).
 */
@Composable
private fun NoIntercomFrame(
    variant: HlThemeVariant,
    layout: WindowLayout,
) {
    HlTheme(variant) {
        CompositionLocalProvider(LocalWindowLayout provides layout) {
            val spacing = HlTheme.spacing
            val tabs = TOP_LEVEL_DESTINATIONS.map { HlTab(it.icon, stringResource(it.label)) }
            val selected = tabIndex(IntercomRoute)
            HlBackground(Modifier.fillMaxSize()) {
                if (layout == WindowLayout.PANES) {
                    IntercomScreen(
                        IntercomUiState(),
                        NoIntercomActions,
                        PaddingValues(end = HlTheme.sizes.tabRail + spacing.s16 * 2),
                    )
                    HlTabRail(tabs, selected, {}, Modifier.align(Alignment.CenterEnd).padding(end = spacing.s16))
                } else {
                    IntercomScreen(
                        IntercomUiState(),
                        NoIntercomActions,
                        PaddingValues(bottom = HlTheme.sizes.tabBar + spacing.s20 * 2),
                    )
                    HlTabBar(
                        tabs,
                        selected,
                        {},
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s20),
                    )
                }
            }
        }
    }
}

@Preview(name = "Devices · none · Night", widthDp = 411, heightDp = 856)
@Composable
private fun NoIntercomNightPreview() = NoIntercomFrame(HlThemeVariant.NIGHT, WindowLayout.COLUMN)

@Preview(name = "Devices · none · Bento", widthDp = 411, heightDp = 856)
@Composable
private fun NoIntercomBentoPreview() = NoIntercomFrame(HlThemeVariant.BENTO, WindowLayout.COLUMN)

@Preview(name = "Devices · none · landscape Night", widthDp = 817, heightDp = 393)
@Composable
private fun NoIntercomLandscapeNightPreview() = NoIntercomFrame(HlThemeVariant.NIGHT, WindowLayout.PANES)

@Preview(name = "Devices · none · landscape Bento", widthDp = 817, heightDp = 393)
@Composable
private fun NoIntercomLandscapeBentoPreview() = NoIntercomFrame(HlThemeVariant.BENTO, WindowLayout.PANES)

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

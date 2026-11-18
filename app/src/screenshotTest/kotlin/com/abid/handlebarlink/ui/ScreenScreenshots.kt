package com.abid.handlebarlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlTab
import com.abid.handlebarlink.core.designsystem.component.HlTabBar
import com.abid.handlebarlink.core.designsystem.preview.LargeText
import com.abid.handlebarlink.core.designsystem.preview.ScreenSizes
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.devices.AssistantHandler
import com.abid.handlebarlink.devices.DeviceKind
import com.abid.handlebarlink.devices.NearbyDevice
import com.abid.handlebarlink.ui.carplay.CarPlayScreen
import com.abid.handlebarlink.ui.carplay.CarPlayUiState
import com.abid.handlebarlink.ui.carplay.PreviewCarPlayConnecting
import com.abid.handlebarlink.ui.carplay.PreviewCarPlayOff
import com.abid.handlebarlink.ui.devices.AddDeviceActions
import com.abid.handlebarlink.ui.devices.AddDeviceScreen
import com.abid.handlebarlink.ui.devices.AddDeviceUiState
import com.abid.handlebarlink.ui.devices.DeviceChoice
import com.abid.handlebarlink.ui.devices.DeviceSheet
import com.abid.handlebarlink.ui.devices.PairDeviceActions
import com.abid.handlebarlink.ui.devices.PairDeviceScreen
import com.abid.handlebarlink.ui.devices.PairDeviceUiState
import com.abid.handlebarlink.ui.devices.SheetDevice
import com.abid.handlebarlink.ui.handlebar.HandlebarPicker
import com.abid.handlebarlink.ui.handlebar.HandlebarScreen
import com.abid.handlebarlink.ui.handlebar.previewHandlebar
import com.abid.handlebarlink.ui.handlebar.previewPicker
import com.abid.handlebarlink.ui.home.HomeScreen
import com.abid.handlebarlink.ui.home.HomeUiState
import com.abid.handlebarlink.ui.home.LinkStatus
import com.abid.handlebarlink.ui.intercom.IntercomScreen
import com.abid.handlebarlink.ui.intercom.IntercomUiState
import com.abid.handlebarlink.ui.intercom.NoIntercomActions
import com.abid.handlebarlink.ui.intercom.PreviewIntercom
import com.abid.handlebarlink.ui.later.LaterScreen
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.settings.ModeScreen
import com.abid.handlebarlink.ui.settings.NoActions
import com.abid.handlebarlink.ui.settings.PermissionsScreen
import com.abid.handlebarlink.ui.settings.PreviewAccess
import com.abid.handlebarlink.ui.settings.PreviewSettings
import com.abid.handlebarlink.ui.settings.SettingsScreen
import com.abid.handlebarlink.ui.settings.ThemeScreen
import com.android.tools.screenshot.PreviewTest

// Every page in both looks at the nine sizes and with large text (testing-setup skill, Step 8). Pages are
// framed like AppRoot (background, floating tab bar, room for it) without its Hilt view models.

@Composable
private fun TabFrame(
    variant: HlThemeVariant,
    selected: Int,
    page: @Composable (PaddingValues) -> Unit,
) {
    HlTheme(variant) {
        val spacing = HlTheme.spacing
        HlBackground(Modifier.fillMaxSize()) {
            page(PaddingValues(bottom = HlTheme.sizes.tabBar + spacing.s20 * 2))
            HlTabBar(
                tabs = TOP_LEVEL_DESTINATIONS.map { HlTab(it.icon, stringResource(it.label)) },
                selected = selected,
                onSelect = {},
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s20)
                        .widthIn(max = HlTheme.sizes.contentMax),
            )
        }
    }
}

@Composable
private fun Home(variant: HlThemeVariant) =
    TabFrame(
        variant,
        selected = 0,
    ) { HomeScreen(HomeUiState(LinkStatus.CONNECTED), onSettings = {}, contentPadding = it) }

@Composable
private fun Garage(variant: HlThemeVariant) =
    TabFrame(variant, selected = 3) { LaterScreen(HlIcons.Garage, R.string.tab_garage, R.string.later_garage, it) }

@Composable
private fun Settings(variant: HlThemeVariant) =
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) {
            SettingsScreen(
                PreviewSettings.copy(theme = if (variant == HlThemeVariant.NIGHT) AppTheme.NIGHT else AppTheme.BENTO),
                PreviewAccess,
                NoActions,
            )
        }
    }

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun HomeNight() = Home(HlThemeVariant.NIGHT)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun HomeBento() = Home(HlThemeVariant.BENTO)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun EmptyTabNight() = Garage(HlThemeVariant.NIGHT)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun EmptyTabBento() = Garage(HlThemeVariant.BENTO)

// The CarPlay tab: the beta switch off (CarLink runs CarPlay) at every size, and on while connecting.
@Composable
private fun CarPlay(
    variant: HlThemeVariant,
    state: CarPlayUiState,
) = TabFrame(variant, selected = 2) { CarPlayScreen(state, onToggle = {}, contentPadding = it) }

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun CarPlayOffNight() = CarPlay(HlThemeVariant.NIGHT, PreviewCarPlayOff)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun CarPlayOffBento() = CarPlay(HlThemeVariant.BENTO, PreviewCarPlayOff)

@PreviewTest
@Artboard
@LargeText
@Composable
fun CarPlayConnectingNight() = CarPlay(HlThemeVariant.NIGHT, PreviewCarPlayConnecting)

@PreviewTest
@Artboard
@LargeText
@Composable
fun CarPlayConnectingBento() = CarPlay(HlThemeVariant.BENTO, PreviewCarPlayConnecting)

// Home's CarPlay card with our own receiver connecting (off, it keeps CarLink's line: HomeNight, HomeBento).
@Composable
private fun HomeCarPlay(variant: HlThemeVariant) =
    TabFrame(variant, selected = 0) {
        HomeScreen(
            HomeUiState(LinkStatus.CONNECTED, carPlay = PreviewCarPlayConnecting.line),
            onSettings = {},
            contentPadding = it,
        )
    }

@PreviewTest
@Artboard
@Composable
fun HomeCarPlayNight() = HomeCarPlay(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@Composable
fun HomeCarPlayBento() = HomeCarPlay(HlThemeVariant.BENTO)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun SettingsNight() = Settings(HlThemeVariant.NIGHT)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun SettingsBento() = Settings(HlThemeVariant.BENTO)

private fun themeOf(variant: HlThemeVariant) = if (variant == HlThemeVariant.NIGHT) AppTheme.NIGHT else AppTheme.BENTO

@Composable
private fun Theme(variant: HlThemeVariant) =
    HlTheme(variant) { HlBackground(Modifier.fillMaxSize()) { ThemeScreen(themeOf(variant), {}, {}) } }

@Composable
private fun Mode(variant: HlThemeVariant) =
    HlTheme(variant) { HlBackground(Modifier.fillMaxSize()) { ModeScreen(themeOf(variant), {}) } }

@PreviewTest
@Artboard
@LargeText
@Composable
fun ThemeNight() = Theme(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun ThemeBento() = Theme(HlThemeVariant.BENTO)

@PreviewTest
@Artboard
@LargeText
@Composable
fun ModeNight() = Mode(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun ModeBento() = Mode(HlThemeVariant.BENTO)

@Composable
private fun Permissions(variant: HlThemeVariant) =
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) { PermissionsScreen(PreviewAccess, {}, {}) }
    }

@PreviewTest
@Artboard
@LargeText
@Composable
fun PermissionsNight() = Permissions(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun PermissionsBento() = Permissions(HlThemeVariant.BENTO)

private val PreviewDevices =
    AddDeviceUiState.Ready(
        audio =
            listOf(
                DeviceChoice(
                    "AA",
                    "EJEAS V6 Pro+",
                    "EJEAS V6 Pro+",
                    connected = true,
                    battery = 50,
                    added = false,
                    kind = DeviceKind.INTERCOM,
                    audio = true,
                ),
                DeviceChoice(
                    "BB",
                    "Buds",
                    "Galaxy Buds2",
                    connected = false,
                    added = true,
                    kind = DeviceKind.HEADSET,
                    audio = true,
                ),
            ),
        other =
            listOf(
                DeviceChoice(
                    "CC",
                    "Galaxy Watch",
                    "Galaxy Watch",
                    connected = false,
                    added = false,
                    kind = DeviceKind.OTHER,
                    audio = false,
                ),
            ),
    )

@Composable
private fun AddDevice(variant: HlThemeVariant) =
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) {
            AddDeviceScreen(PreviewDevices, Access.GRANTED, AddDeviceActions({}, {}, {}, {}, {}))
        }
    }

@PreviewTest
@Artboard
@LargeText
@Composable
fun AddDeviceNight() = AddDevice(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun AddDeviceBento() = AddDevice(HlThemeVariant.BENTO)

@Composable
private fun WhatIsIt(variant: HlThemeVariant) =
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

@PreviewTest
@Artboard
@LargeText
@Composable
fun WhatIsItNight() = WhatIsIt(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun WhatIsItBento() = WhatIsIt(HlThemeVariant.BENTO)

private val PreviewNearby =
    PairDeviceUiState.Nearby(
        audio = listOf(NearbyDevice("AA", "EJEAS V6 Pro+", audio = true, suggested = DeviceKind.INTERCOM)),
        other = listOf(NearbyDevice("CC", "Living room TV", audio = false, suggested = DeviceKind.OTHER)),
        searching = false,
        attempt = null,
    )

@Composable
private fun PairDevice(variant: HlThemeVariant) =
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) {
            PairDeviceScreen(PreviewNearby, Access.GRANTED, PairDeviceActions({}, {}, {}, {}, {}, {}))
        }
    }

@PreviewTest
@Artboard
@LargeText
@Composable
fun PairDeviceNight() = PairDevice(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun PairDeviceBento() = PairDevice(HlThemeVariant.BENTO)

@Composable
private fun Handlebar(
    variant: HlThemeVariant,
    situation: CallSituation,
) = HlTheme(variant) {
    HlBackground(Modifier.fillMaxSize()) {
        // Riding has a remapped up press and the free left press, so both the set and the dimmed look show.
        HandlebarScreen(previewHandlebar(situation), onSituation = {}, onPress = {}, onReset = {}, onBack = {})
    }
}

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarNight() = Handlebar(HlThemeVariant.NIGHT, CallSituation.RIDING)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarBento() = Handlebar(HlThemeVariant.BENTO, CallSituation.RIDING)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarRingingNight() = Handlebar(HlThemeVariant.NIGHT, CallSituation.RINGING)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarRingingBento() = Handlebar(HlThemeVariant.BENTO, CallSituation.RINGING)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarOnCallNight() = Handlebar(HlThemeVariant.NIGHT, CallSituation.ON_CALL)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarOnCallBento() = Handlebar(HlThemeVariant.BENTO, CallSituation.ON_CALL)

@Composable
private fun HandlebarPickerSheet(
    variant: HlThemeVariant,
    situation: CallSituation,
) = HlTheme(variant) { HandlebarPicker(previewPicker(situation), onChoose = {}, onCancel = {}) }

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarPickerNight() = HandlebarPickerSheet(HlThemeVariant.NIGHT, CallSituation.ON_CALL)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarPickerBento() = HandlebarPickerSheet(HlThemeVariant.BENTO, CallSituation.ON_CALL)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HandlebarPickerRidingNight() = HandlebarPickerSheet(HlThemeVariant.NIGHT, CallSituation.RIDING)

@Composable
private fun Intercom(
    variant: HlThemeVariant,
    state: IntercomUiState,
) = HlTheme(variant) {
    HlBackground(Modifier.fillMaxSize()) { IntercomScreen(state, NoIntercomActions, PaddingValues()) }
}

@PreviewTest
@Artboard
@LargeText
@Composable
fun IntercomNight() = Intercom(HlThemeVariant.NIGHT, PreviewIntercom)

@PreviewTest
@Artboard
@LargeText
@Composable
fun IntercomBento() = Intercom(HlThemeVariant.BENTO, PreviewIntercom.copy(handler = AssistantHandler.Other("Google")))

@PreviewTest
@Artboard
@LargeText
@Composable
fun IntercomNone() = Intercom(HlThemeVariant.NIGHT, IntercomUiState())

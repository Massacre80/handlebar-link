package com.abid.handlebarlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlTab
import com.abid.handlebarlink.core.designsystem.component.HlTabRail
import com.abid.handlebarlink.core.designsystem.preview.Landscape
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.setup.FoundMeter
import com.abid.handlebarlink.setup.SetupProblem
import com.abid.handlebarlink.setup.SetupStep
import com.abid.handlebarlink.setup.SetupUiState
import com.abid.handlebarlink.ui.carplay.CarPlayScreen
import com.abid.handlebarlink.ui.carplay.CarPlayUiState
import com.abid.handlebarlink.ui.carplay.PairIphoneScreen
import com.abid.handlebarlink.ui.carplay.PairIphoneUiState
import com.abid.handlebarlink.ui.carplay.PreviewCarPlayConnected
import com.abid.handlebarlink.ui.carplay.PreviewCarPlayRetrying
import com.abid.handlebarlink.ui.carplay.PreviewCarPlayWifiOff
import com.abid.handlebarlink.ui.carplay.PreviewPairActions
import com.abid.handlebarlink.ui.carplay.PreviewPairPaired
import com.abid.handlebarlink.ui.carplay.PreviewPairSteps
import com.abid.handlebarlink.ui.carplay.PreviewPairWaiting
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.PreviewTabFrame
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.handlebar.HandlebarScreen
import com.abid.handlebarlink.ui.handlebar.previewHandlebar
import com.abid.handlebarlink.ui.home.HomeScreen
import com.abid.handlebarlink.ui.home.HomeUiState
import com.abid.handlebarlink.ui.home.LinkStatus
import com.abid.handlebarlink.ui.intercom.IntercomScreen
import com.abid.handlebarlink.ui.intercom.IntercomUiState
import com.abid.handlebarlink.ui.intercom.NoIntercomActions
import com.abid.handlebarlink.ui.intercom.PreviewIntercom
import com.abid.handlebarlink.ui.navigation.CarPlayRoute
import com.abid.handlebarlink.ui.navigation.HomeRoute
import com.abid.handlebarlink.ui.navigation.IntercomRoute
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import com.abid.handlebarlink.ui.navigation.tabIndex
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.settings.NoActions
import com.abid.handlebarlink.ui.settings.PreviewAccess
import com.abid.handlebarlink.ui.settings.PreviewSettings
import com.abid.handlebarlink.ui.settings.SettingsScreen
import com.abid.handlebarlink.ui.setup.SetupAccess
import com.abid.handlebarlink.ui.setup.SetupActions
import com.abid.handlebarlink.ui.setup.SetupScreen
import com.android.tools.screenshot.PreviewTest

// The phone on its side (docs/PLAN.md "Landscape layout"): the pages in two panes beside the tab rail, at the
// emulator's and the Poco's landscape sizes (@Landscape). Framed like AppRoot in WindowLayout.PANES, without its Hilt
// view models; previews draw no system bars or cutout, so the mirrored sides are zero here.

@Composable
private fun Panes(
    variant: HlThemeVariant,
    content: @Composable () -> Unit,
) {
    HlTheme(variant) {
        CompositionLocalProvider(LocalWindowLayout provides WindowLayout.PANES) {
            HlBackground(Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun RailFrame(
    variant: HlThemeVariant,
    selected: Int,
    page: @Composable (PaddingValues) -> Unit,
) = Panes(variant) { PreviewTabFrame(selected, page = page) }

@Composable
private fun Home(variant: HlThemeVariant) =
    RailFrame(variant, selected = tabIndex(HomeRoute)) {
        HomeScreen(HomeUiState(LinkStatus.CONNECTED), contentPadding = it)
    }

@PreviewTest
@Landscape
@Composable
fun HomeNightLandscape() = Home(HlThemeVariant.NIGHT)

@PreviewTest
@Landscape
@Composable
fun HomeBentoLandscape() = Home(HlThemeVariant.BENTO)

@Composable
private fun CarPlay(
    variant: HlThemeVariant,
    state: CarPlayUiState,
) = RailFrame(variant, selected = tabIndex(CarPlayRoute)) {
    CarPlayScreen(state, onAction = {}, onIphone = {}, contentPadding = it)
}

@PreviewTest
@Landscape
@Composable
fun CarPlayConnectedNightLandscape() = CarPlay(HlThemeVariant.NIGHT, PreviewCarPlayConnected)

@PreviewTest
@Landscape
@Composable
fun CarPlayWifiOffBentoLandscape() = CarPlay(HlThemeVariant.BENTO, PreviewCarPlayWifiOff)

/** Paper "Tab 8L": a retry counting down, Try now over Cancel, the line only "Retrying in 8 s". */
@PreviewTest
@Landscape
@Composable
fun CarPlayRetryingNightLandscape() = CarPlay(HlThemeVariant.NIGHT, PreviewCarPlayRetrying)

// Devices and the tabs still to come fill the frame between the start margin and the rail, as Home does.

@PreviewTest
@Landscape
@Composable
fun DevicesNightLandscape() =
    RailFrame(HlThemeVariant.NIGHT, selected = tabIndex(IntercomRoute)) {
        IntercomScreen(PreviewIntercom, NoIntercomActions, it)
    }

// Nothing added (Paper "Devices 1L"): the hero | Add device and the rows, as the CarPlay tab's panes.
@Composable
private fun NoIntercom(variant: HlThemeVariant) =
    RailFrame(variant, selected = tabIndex(IntercomRoute)) {
        IntercomScreen(IntercomUiState(), NoIntercomActions, it)
    }

@PreviewTest
@Landscape
@Composable
fun DevicesNoneNightLandscape() = NoIntercom(HlThemeVariant.NIGHT)

@PreviewTest
@Landscape
@Composable
fun DevicesNoneBentoLandscape() = NoIntercom(HlThemeVariant.BENTO)

private val LandscapeMeter = FoundMeter("HONDA BTU 2040", "AA:BB:CC:DD:EE:FF", rssi = -55)

@Composable
private fun Setup(
    variant: HlThemeVariant,
    state: SetupUiState,
) = Panes(variant) {
    SetupScreen(state, SetupAccess(Access.ASK), SetupActions({}, {}, {}, {}, {}, {}, {}, {}))
}

@PreviewTest
@Landscape
@Composable
fun SetupWelcomeNightLandscape() = Setup(HlThemeVariant.NIGHT, SetupUiState())

@PreviewTest
@Landscape
@Composable
fun SetupWelcomeBentoLandscape() = Setup(HlThemeVariant.BENTO, SetupUiState())

@PreviewTest
@Landscape
@Composable
fun SetupReadyNightLandscape() =
    Setup(HlThemeVariant.NIGHT, SetupUiState(SetupStep.READY, problem = SetupProblem.BLUETOOTH_OFF))

@PreviewTest
@Landscape
@Composable
fun SetupChooseNightLandscape() =
    Setup(HlThemeVariant.NIGHT, SetupUiState(SetupStep.CHOOSE, meters = listOf(LandscapeMeter), searching = true))

@PreviewTest
@Landscape
@Composable
fun SetupPairingNightLandscape() =
    Setup(
        HlThemeVariant.NIGHT,
        SetupUiState(SetupStep.PAIRING, chosen = LandscapeMeter, problem = SetupProblem.PAIRING_FAILED),
    )

@PreviewTest
@Landscape
@Composable
fun SetupDoneNightLandscape() = Setup(HlThemeVariant.NIGHT, SetupUiState(SetupStep.DONE, chosen = LandscapeMeter))

@PreviewTest
@Landscape
@Composable
fun SetupLinkingBentoLandscape() = Setup(HlThemeVariant.BENTO, SetupUiState(SetupStep.LINKING, chosen = LandscapeMeter))

@PreviewTest
@Landscape
@Composable
fun SetupDoneBentoLandscape() = Setup(HlThemeVariant.BENTO, SetupUiState(SetupStep.DONE, chosen = LandscapeMeter))

// The in-app iPhone pairing (CarPlay tab), built from setup's panes: the cards share the heading's 24 dp line.

@Composable
private fun PairIphone(
    variant: HlThemeVariant,
    state: PairIphoneUiState,
) = Panes(variant) { PairIphoneScreen(state, "CB350RS", PreviewPairActions) }

@PreviewTest
@Landscape
@Composable
fun PairIphoneStepsNightLandscape() = PairIphone(HlThemeVariant.NIGHT, PreviewPairSteps)

@PreviewTest
@Landscape
@Composable
fun PairIphoneWaitingBentoLandscape() = PairIphone(HlThemeVariant.BENTO, PreviewPairWaiting)

@PreviewTest
@Landscape
@Composable
fun PairIphonePairedNightLandscape() = PairIphone(HlThemeVariant.NIGHT, PreviewPairPaired)

@PreviewTest
@Landscape
@Composable
fun HandlebarNightLandscape() =
    Panes(HlThemeVariant.NIGHT) {
        HandlebarScreen(
            previewHandlebar(CallSituation.RIDING),
            onSituation = {},
            onPress = {},
            onReset = {},
            onBack = {},
        )
    }

@PreviewTest
@Landscape
@Composable
fun HandlebarBentoLandscape() =
    Panes(HlThemeVariant.BENTO) {
        HandlebarScreen(
            previewHandlebar(CallSituation.RINGING),
            onSituation = {},
            onPress = {},
            onReset = {},
            onBack = {},
        )
    }

@PreviewTest
@Landscape
@Composable
fun SettingsNightLandscape() =
    Panes(
        HlThemeVariant.NIGHT,
    ) { SettingsScreen(PreviewSettings.copy(theme = AppTheme.NIGHT), PreviewAccess, NoActions) }

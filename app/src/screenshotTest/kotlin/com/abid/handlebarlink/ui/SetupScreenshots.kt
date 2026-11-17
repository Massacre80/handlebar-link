package com.abid.handlebarlink.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.preview.LargeText
import com.abid.handlebarlink.core.designsystem.preview.ScreenSizes
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.setup.FoundMeter
import com.abid.handlebarlink.setup.SetupProblem
import com.abid.handlebarlink.setup.SetupStep
import com.abid.handlebarlink.setup.SetupUiState
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.setup.SetupAccess
import com.abid.handlebarlink.ui.setup.SetupActions
import com.abid.handlebarlink.ui.setup.SetupScreen
import com.android.tools.screenshot.PreviewTest

// The setup states (Paper "Setup") without the Hilt view model. Night, the default look, at the artboard's
// size and with large text; Welcome and "You're set" (the bike stages) also at the nine sizes. Bento at the
// artboard's size.

/** The Paper artboard: 411 × 856. */
@Preview(name = "artboard", widthDp = 411, heightDp = 856)
annotation class Artboard

private val Meter = FoundMeter("HONDA BTU 2040", "AA:BB:CC:DD:EE:FF", rssi = -55)

private val NoActions = SetupActions({}, {}, {}, {}, {}, {}, {}, {}, {})

@Composable
private fun Setup(
    variant: HlThemeVariant,
    state: SetupUiState,
    access: SetupAccess = SetupAccess(Access.GRANTED, Access.ASK),
) {
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) { SetupScreen(state, access, NoActions) }
    }
}

private val Welcome = SetupUiState()
private val ReadyProblem = SetupUiState(SetupStep.READY, problem = SetupProblem.BLUETOOTH_OFF)
private val Searching = SetupUiState(SetupStep.CHOOSE, meters = listOf(Meter), searching = true)
private val NoneFound = SetupUiState(SetupStep.CHOOSE)
private val PairingFailed =
    SetupUiState(
        SetupStep.PAIRING,
        chosen = Meter,
        linkedAddress = Meter.address,
        problem = SetupProblem.PAIRING_FAILED,
    )
private val Done = SetupUiState(SetupStep.DONE, chosen = Meter, linkedAddress = Meter.address)

@PreviewTest
@Artboard
@ScreenSizes
@LargeText
@Composable
fun SetupWelcomeNight() = Setup(HlThemeVariant.NIGHT, Welcome)

@PreviewTest
@Artboard
@LargeText
@Composable
fun SetupReadyProblemNight() = Setup(HlThemeVariant.NIGHT, ReadyProblem)

@PreviewTest
@Artboard
@LargeText
@Composable
fun SetupSearchingNight() = Setup(HlThemeVariant.NIGHT, Searching)

@PreviewTest
@Artboard
@LargeText
@Composable
fun SetupNoneFoundNight() = Setup(HlThemeVariant.NIGHT, NoneFound)

@PreviewTest
@Artboard
@LargeText
@Composable
fun SetupPairingFailedNight() = Setup(HlThemeVariant.NIGHT, PairingFailed)

@PreviewTest
@Artboard
@ScreenSizes
@LargeText
@Composable
fun SetupDoneNight() = Setup(HlThemeVariant.NIGHT, Done)

@PreviewTest
@Artboard
@Composable
fun SetupWelcomeBento() = Setup(HlThemeVariant.BENTO, Welcome)

@PreviewTest
@Artboard
@Composable
fun SetupReadyProblemBento() = Setup(HlThemeVariant.BENTO, ReadyProblem)

@PreviewTest
@Artboard
@Composable
fun SetupSearchingBento() = Setup(HlThemeVariant.BENTO, Searching)

@PreviewTest
@Artboard
@Composable
fun SetupNoneFoundBento() = Setup(HlThemeVariant.BENTO, NoneFound)

@PreviewTest
@Artboard
@Composable
fun SetupPairingFailedBento() = Setup(HlThemeVariant.BENTO, PairingFailed)

@PreviewTest
@Artboard
@Composable
fun SetupDoneBento() = Setup(HlThemeVariant.BENTO, Done)

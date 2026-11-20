package com.abid.handlebarlink.ui.carplay

import android.Manifest
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import java.time.Instant

// The BikePlay tab's nine states as Paper "BikePlay — flow" draws them (Tab 1–9, 1L–9L), for previews and screenshot
// tests.

/** The countdown of Paper "Tab 8": 55 % of the pause left. */
private val RetryNow: Instant = Instant.EPOCH
private val Retrying =
    CarPlayState.Recovering(
        CarPlayError.TimedOut(ConnectStep.PAIRING),
        2,
        RetryNow.plusMillis(PREVIEW_LEFT_MS),
        failedAt = RetryNow.minusMillis(PREVIEW_PAUSE_MS - PREVIEW_LEFT_MS),
    )
private val RetryLine =
    CarPlayLine(
        R.string.carplay_status_retrying,
        R.string.carplay_error_timed_out,
        PREVIEW_SECONDS,
        SignalTone.PROGRESS,
    )

/** The nine states (Tab 1–9) in order. */
internal val PreviewTabStates: List<CarPlayUiState> =
    listOf(
        PreviewCarPlayConnected,
        tab(CarPlayStatus.Engine(CarPlayState.Idle)),
        tab(CarPlayStatus.Engine(CarPlayState.Connecting(ConnectStep.WAITING_FOR_PHONE))),
        tab(CarPlayStatus.Engine(CarPlayState.Stopped), iphones = false),
        tab(CarPlayStatus.Engine(CarPlayState.Recovering(CarPlayError.WifiOff, 1, RetryNow))),
        tab(
            CarPlayStatus.Engine(CarPlayState.Recovering(CarPlayError.BluetoothUnavailable("off"), 1, RetryNow)),
            bluetoothOn = false,
        ),
        tab(CarPlayStatus.NeedsPermission(listOf(Manifest.permission.BLUETOOTH_CONNECT))),
        tab(CarPlayStatus.Engine(Retrying), line = RetryLine),
        tab(CarPlayStatus.Engine(CarPlayState.Stopped)),
    )

private fun tab(
    status: CarPlayStatus,
    iphones: Boolean = true,
    bluetoothOn: Boolean = true,
    line: CarPlayLine = CarPlayLine(R.string.carplay_status_connected, tone = SignalTone.ON),
) = carPlayTab(
    status,
    if (iphones) listOf(PreviewIphone) else emptyList(),
    PreviewIphone.address.takeIf { iphones },
    line,
    bluetoothOn = bluetoothOn,
    now = RetryNow,
)

/** Paper "Tab 8": a retry counting down. */
internal val PreviewCarPlayRetrying: CarPlayUiState = PreviewTabStates[RETRYING_INDEX]

/** Every state, for previews. */
internal class TabStates : PreviewParameterProvider<CarPlayUiState> {
    override val values: Sequence<CarPlayUiState> = PreviewTabStates.asSequence()
}

@Preview(name = "BikePlay tab · Night", widthDp = 411, heightDp = 856)
@Composable
private fun TabNightPreview(
    @PreviewParameter(TabStates::class) state: CarPlayUiState,
) = HlTheme(HlThemeVariant.NIGHT) {
    HlBackground(Modifier.fillMaxSize()) {
        CarPlayScreen(state, {}, {}, PaddingValues(bottom = HlTheme.sizes.tabBar + HlTheme.spacing.s20 * 2))
    }
}

@Preview(name = "BikePlay tab · Night · landscape", widthDp = 817, heightDp = 393)
@Composable
private fun TabNightLandscapePreview(
    @PreviewParameter(TabStates::class) state: CarPlayUiState,
) = HlTheme(HlThemeVariant.NIGHT) {
    CompositionLocalProvider(LocalWindowLayout provides WindowLayout.PANES) {
        HlBackground(Modifier.fillMaxSize()) {
            CarPlayScreen(state, {}, {}, PaddingValues(end = HlTheme.sizes.tabRail + HlTheme.spacing.s16 * 2))
        }
    }
}

/** Paper's arc: 55 % of the pause left. */
private const val PREVIEW_PAUSE_MS = 20_000L
private const val PREVIEW_LEFT_MS = 11_000L

/** Tab 8 in [PreviewTabStates]. */
private const val RETRYING_INDEX = 7

/** Paper "Tab 8": "Retrying in 8 s". */
private const val PREVIEW_SECONDS = 8L

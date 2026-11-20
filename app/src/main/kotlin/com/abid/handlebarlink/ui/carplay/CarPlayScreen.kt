package com.abid.handlebarlink.ui.carplay

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.android.video.CarPlayVideoStats
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.frame.HeroColumn
import com.abid.handlebarlink.ui.frame.HeroState
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.permissions.Access
import java.time.Instant

/**
 * The CarPlay tab (Paper "CarPlay — flow v2", Tab 1–4): a hero (the dial, a state tag, a big title, a detail line,
 * one primary and one ghost action), then the status list: the iPhone (opens the iPhone sheet), the Link and
 * "CarPlay screen" (opens its picker, [onScreen]). No switch: our CarPlay replaced CarLink and always runs with the
 * ride (owner, 2026-11-20). The page scrolls when it runs out of height (docs/LESSONS.md 22).
 */
@Composable
fun CarPlayScreen(
    state: CarPlayUiState,
    onAction: (TabAction) -> Unit,
    onIphone: () -> Unit,
    contentPadding: PaddingValues,
    onScreen: () -> Unit = {},
) {
    if (LocalWindowLayout.current == WindowLayout.PANES) {
        CarPlayPanes(state, onAction, onIphone, onScreen, contentPadding)
        return
    }
    HeroColumn(contentPadding) {
        Hero(state.hero, onAction, Modifier.widthIn(max = HlTheme.sizes.contentMax))
        StatusList(state, onIphone, onScreen, Modifier.widthIn(max = HlTheme.sizes.contentMax))
    }
}

@Composable
private fun Hero(
    hero: CarPlayHero,
    onAction: (TabAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12),
    ) {
        HeroState(hero)
        HeroActions(hero, onAction, Modifier.padding(top = HlTheme.spacing.s8))
    }
}

/** The dial, the state tag, the title and the detail line ([HeroState] with the CarPlay mark). */
@Composable
internal fun ColumnScope.HeroState(hero: CarPlayHero) {
    // The hero changes by itself (starting, running, needs you): TalkBack reads the new title politely. Not the
    // detail, whose retry countdown ticks every second.
    HeroState(
        HlIcons.CarPlay,
        hero.tone,
        hero.progress,
        stringResource(hero.tag),
        stringResource(hero.title),
        hero.detail.words(),
        liveTitle = true,
    )
}

/** The hero's primary and ghost actions, when it has any. */
@Composable
internal fun HeroActions(
    hero: CarPlayHero,
    onAction: (TabAction) -> Unit,
    modifier: Modifier,
) {
    if (hero.primary != null || hero.ghost != null) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
            hero.primary?.let {
                HlButton(stringResource(actionLabel(it)), { onAction(it) }, Modifier.fillMaxWidth(), brand = true)
            }
            hero.ghost?.let { HlSecondaryButton(stringResource(actionLabel(it)), { onAction(it) }) }
        }
    }
}

/** The iPhone, the Link and the CarPlay screen, in one group. */
@Composable
internal fun StatusList(
    state: CarPlayUiState,
    onIphone: () -> Unit,
    onScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    HlGroup(modifier) {
        HlValueRow(
            value = state.iphone.name ?: stringResource(R.string.carplay_iphone_row),
            hint = stringResource(state.iphone.hint),
            detail = stringResource(state.iphone.detail),
            onClick = onIphone,
            container = Color.Transparent,
            chevron = true,
            icon = HlIcons.Phone,
        )
        HlGroupDivider()
        HlValueRow(
            value = stringResource(R.string.carplay_link_row),
            hint = stringResource(state.link.hint),
            detail = state.link.detailWords(),
            container = Color.Transparent,
            hintColor = if (state.link.live) colors.success else Color.Unspecified,
            icon = HlIcons.Wifi,
        )
        HlGroupDivider()
        HlValueRow(
            value = stringResource(R.string.carplay_screen_row),
            hint = stringResource(orientationName(state.orientation)),
            detail = stringResource(R.string.carplay_screen_row_detail),
            onClick = onScreen,
            container = Color.Transparent,
            chevron = true,
            icon = orientationIcon(state.orientation),
        )
    }
}

@StringRes
private fun actionLabel(action: TabAction): Int =
    when (action) {
        TabAction.ADD_IPHONE -> R.string.carplay_action_add
        TabAction.CHOOSE_IPHONE -> R.string.carplay_action_choose
        TabAction.START_CARPLAY -> R.string.carplay_action_start
        TabAction.OPEN_CARPLAY -> R.string.carplay_open
        TabAction.CANCEL -> R.string.cancel
        TabAction.DISCONNECT -> R.string.carplay_action_disconnect
        TabAction.ALLOW -> R.string.carplay_action_allow
        TabAction.OPEN_SETTINGS -> R.string.carplay_action_settings
        TabAction.TURN_ON_WIFI -> R.string.carplay_action_wifi
        TabAction.TURN_ON_BLUETOOTH -> R.string.carplay_action_bluetooth
    }

/** Sample states for previews and screenshot tests (Paper "Tab 1–4"). */
internal val PreviewIphone = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
private val PreviewLine = CarPlayLine(R.string.carplay_status_connected, tone = SignalTone.ON)

internal val PreviewCarPlayNotSetUp =
    carPlayTab(CarPlayStatus.Engine(CarPlayState.Idle), emptyList(), null, PreviewLine)

internal val PreviewCarPlayConnecting =
    carPlayTab(
        CarPlayStatus.Engine(CarPlayState.Connecting(ConnectStep.IDENTIFYING)),
        listOf(PreviewIphone),
        null,
        PreviewLine,
    )

internal val PreviewCarPlayConnected =
    carPlayTab(
        CarPlayStatus.Engine(CarPlayState.Connected("phone", Instant.EPOCH, wifiChannel = 36)),
        listOf(PreviewIphone),
        PreviewIphone.address,
        PreviewLine,
        CarPlayVideoStats(received = 300, shown = 300, framesPerSecond = 60.0, 0, decodeP90Millis = 12.0, 0),
    )

internal val PreviewCarPlayWifiOff =
    carPlayTab(
        CarPlayStatus.Engine(CarPlayState.Recovering(CarPlayError.WifiOff, 1, Instant.EPOCH)),
        listOf(PreviewIphone),
        null,
        PreviewLine,
    )

/** Home's CarPlay line while our receiver connects. */
internal val PreviewCarPlayConnectingLine =
    carPlayLine(CarPlayStatus.Engine(CarPlayState.Connecting(ConnectStep.DIALING_PHONE)), Instant.EPOCH)

/** No ride running: Ready, with Start CarPlay. */
internal val PreviewCarPlayReady =
    carPlayTab(CarPlayStatus.Engine(CarPlayState.Stopped), listOf(PreviewIphone), null, PreviewLine)

/** Stopped after an error: Needs you with Start CarPlay. */
internal val PreviewCarPlayFailed = carPlayTab(CarPlayStatus.Failed, listOf(PreviewIphone), null, PreviewLine)

/** The microphone refused for good: Needs you with Open settings, the screen row in portrait. */
internal val PreviewCarPlayMicrophone =
    carPlayTab(
        CarPlayStatus.NeedsPermission(listOf(Manifest.permission.RECORD_AUDIO)),
        listOf(PreviewIphone),
        null,
        PreviewLine,
        orientation = CarPlayOrientation.PORTRAIT,
    ).let { it.copy(hero = it.hero.withAccess(Access.BLOCKED)) }

@Preview(name = "CarPlay · Night", widthDp = 411, heightDp = 856)
@Composable
private fun CarPlayNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { CarPlayScreen(PreviewCarPlayConnected, {}, {}, PaddingValues()) }
    }
}

@Preview(name = "CarPlay · Bento", widthDp = 411, heightDp = 856)
@Composable
private fun CarPlayBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) { CarPlayScreen(PreviewCarPlayWifiOff, {}, {}, PaddingValues()) }
    }
}

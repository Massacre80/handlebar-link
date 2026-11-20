package com.abid.handlebarlink.ui.carplay

import android.Manifest
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.android.video.CarPlayVideoStats
import com.abid.handlebarlink.core.designsystem.component.HlActionButton
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlButtonKind
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlMeterHero
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.designsystem.theme.HlSpacing
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.frame.HeroColumn
import com.abid.handlebarlink.ui.frame.HeroState
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.frame.pageTop
import com.abid.handlebarlink.ui.permissions.Access
import java.time.Instant

/**
 * The BikePlay tab (Paper "BikePlay — flow", Tab 1–9; on its side Tab 1L–9L, [CarPlayPanes]): the meter with BikePlay's
 * state word and one line (the whole status, shown once), the buttons where the rider can act (8 dp apart), then 14 dp
 * below them the status list: the iPhone (opens the iPhone sheet), the Link and "BikePlay screen" (opens its picker,
 * [onScreen]). No switch: BikePlay always runs with the ride (owner, 2026-11-20). The page scrolls when it runs out of
 * height (docs/LESSONS.md 22).
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
    val spacing = HlTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.pageTop)
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(start = spacing.s16, end = spacing.s16, top = HlTheme.sizes.heroTop, bottom = spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val width = Modifier.widthIn(max = HlTheme.sizes.contentMax).fillMaxWidth()
        BikePlayHero(state, pane = false)
        val buttons = tabButtons(state.card)
        TabButtons(buttons, onAction, width.padding(top = spacing.s28))
        StatusList(
            state,
            onIphone,
            onScreen,
            width.padding(top = if (buttons.isEmpty()) spacing.s28 else spacing.groupGap),
        )
    }
}

/**
 * The meter with BikePlay's state word and its line ([HlMeterHero]); on its side ([pane]) the smaller meter, and a
 * retry's line keeps only its countdown (Paper "Tab 8L": "Retrying in 8 s"). Read as one heading; TalkBack announces a
 * new state politely, except while a retry counts down (its line changes every second).
 */
@Composable
internal fun BikePlayHero(
    state: CarPlayUiState,
    pane: Boolean,
    modifier: Modifier = Modifier,
) {
    val card = state.card
    HlMeterHero(
        stringResource(card.word),
        tabDetail(card, pane),
        card.meter,
        card.tone,
        modifier.semantics(mergeDescendants = true) {
            heading()
            if (card.phase != CardPhase.RETRYING) liveRegion = LiveRegionMode.Polite
        },
        pane = pane,
    )
}

/**
 * The tab's line under the state word: the card's, but "<iPhone> · Wi-Fi Direct" connected and only the countdown
 * on its side.
 */
@Composable
private fun tabDetail(
    card: BikePlayCardState,
    pane: Boolean,
): String {
    val detail = card.detail
    return when {
        card.phase == CardPhase.CONNECTED && detail is HeroDetail.Name -> {
            stringResource(R.string.carplay_tab_connected_detail, detail.name)
        }

        card.phase == CardPhase.CONNECTED -> {
            stringResource(R.string.bikeplay_tab_connected_any)
        }

        pane && detail is HeroDetail.Line && detail.line.seconds != null -> {
            stringResource(R.string.bikeplay_retrying_in, detail.line.seconds)
        }

        else -> {
            detail.words()
        }
    }
}

/** The tab's buttons, stacked 8 dp apart ([HlSpacing.buttonStack]); nothing when there are none. */
@Composable
internal fun TabButtons(
    buttons: List<TabButton>,
    onAction: (TabAction) -> Unit,
    modifier: Modifier,
) {
    if (buttons.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.buttonStack)) {
        buttons.forEach { button ->
            HlActionButton(
                stringResource(actionLabel(button.action)),
                { onAction(button.action) },
                button.kind,
                icon = button.icon,
            )
        }
    }
}

/**
 * The iPhone, the Link and the BikePlay screen, in one group; [compact] (on its side) with 12 dp rows. The Link row's
 * trailing word is the link's quality while connected (in the brand colour), nothing otherwise: the meter already says
 * whether BikePlay is connected.
 */
@Composable
internal fun StatusList(
    state: CarPlayUiState,
    onIphone: () -> Unit,
    onScreen: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
            compact = compact,
        )
        HlGroupDivider()
        HlValueRow(
            value = stringResource(R.string.carplay_link_row),
            hint =
                state.link.quality
                    ?.takeIf { state.link.live }
                    ?.let { stringResource(it.words) },
            detail = state.link.detailWords(),
            container = Color.Transparent,
            hintColor = colors.brand,
            icon = HlIcons.Wifi,
            compact = compact,
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
            compact = compact,
        )
    }
}

/** What a hero or card button says for [action]. */
@StringRes
internal fun actionLabel(action: TabAction): Int =
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
        TabAction.RETRY_NOW -> R.string.carplay_action_try_now
    }

/** One of the tab's buttons: what it does and how it looks. */
@Immutable
data class TabButton(
    val action: TabAction,
    val kind: HlButtonKind,
    @param:DrawableRes val icon: Int? = null,
)

/**
 * The tab's buttons for the [card]'s state (Paper "BikePlay — flow", owner 2026-11-20: what the rider does is lime,
 * dismissing is the secondary button, ending is the red one): Connected, Open BikePlay (no icon) over Disconnect;
 * starting or connecting, Cancel; a retry counting down, Try now over Cancel; otherwise the card's one action (Add
 * iPhone with its plus, Start BikePlay with its play mark, a fix).
 */
internal fun tabButtons(card: BikePlayCardState): List<TabButton> =
    when (card.phase) {
        CardPhase.CONNECTED -> {
            listOf(
                TabButton(TabAction.OPEN_CARPLAY, HlButtonKind.PRIMARY),
                TabButton(TabAction.DISCONNECT, HlButtonKind.DESTRUCTIVE),
            )
        }

        CardPhase.STARTING, CardPhase.CONNECTING -> {
            listOf(TabButton(TabAction.CANCEL, HlButtonKind.SECONDARY))
        }

        CardPhase.RETRYING -> {
            listOf(
                TabButton(TabAction.RETRY_NOW, HlButtonKind.PRIMARY),
                TabButton(TabAction.CANCEL, HlButtonKind.SECONDARY),
            )
        }

        else -> {
            listOfNotNull(card.action?.let { TabButton(it, HlButtonKind.PRIMARY, actionIcon(it)) })
        }
    }

/** The icon before a lime button's words: Add iPhone's plus, Start BikePlay's play mark. */
@DrawableRes
internal fun actionIcon(action: TabAction): Int? =
    when (action) {
        TabAction.ADD_IPHONE -> HlIcons.PlusBold
        TabAction.START_CARPLAY -> HlIcons.Play
        else -> null
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

/** Home's BikePlay card connected to the preview iPhone on channel 36 with a strong link (Paper "B · 1"). */
internal val PreviewBikePlayConnected =
    bikePlayCard(
        CarPlayStatus.Engine(CarPlayState.Connected("phone", Instant.EPOCH, wifiChannel = 36)),
        listOf(PreviewIphone),
        PreviewIphone.address,
        PreviewLine,
        LinkQuality.STRONG,
        bluetoothOn = true,
        now = Instant.EPOCH,
    )

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
    ).let { it.copy(hero = it.hero.withAccess(Access.BLOCKED), card = it.card.withAccess(Access.BLOCKED)) }

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

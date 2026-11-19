package com.abid.handlebarlink.ui.carplay

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlTag
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.component.SignalRing
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import java.time.Instant

/**
 * The CarPlay tab (Paper "CarPlay — flow v2", Tab 1–4): a hero (the dial, a state tag, a big title, a detail line,
 * one primary and one ghost action), then the status list: the iPhone (opens the iPhone sheet), the Link, and the
 * "Handlebar Link CarPlay · Beta · instead of CarLink" switch. The page scrolls when it runs out of height
 * (docs/LESSONS.md 22).
 */
@Composable
fun CarPlayScreen(
    state: CarPlayUiState,
    onAction: (TabAction) -> Unit,
    onToggle: () -> Unit,
    onIphone: () -> Unit,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(contentPadding),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = spacing.s16, vertical = spacing.s16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s24, Alignment.CenterVertically),
        ) {
            Hero(state.hero, onAction, Modifier.widthIn(max = HlTheme.sizes.contentMax))
            StatusList(state, onToggle, onIphone, Modifier.widthIn(max = HlTheme.sizes.contentMax))
        }
    }
}

@Composable
private fun Hero(
    hero: CarPlayHero,
    onAction: (TabAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        HeroDial(hero.tone, hero.progress)
        StateTag(stringResource(hero.tag), hero.tone, Modifier.padding(top = spacing.s4))
        HlText(
            stringResource(hero.title),
            HlTheme.typography.headline.copy(textAlign = TextAlign.Center),
            colors.ink,
            Modifier.semantics { heading() },
        )
        HlText(
            hero.detail.words(),
            HlTheme.typography.body.copy(textAlign = TextAlign.Center),
            colors.inkMuted,
            Modifier.padding(horizontal = spacing.s8),
        )
        if (hero.primary != null || hero.ghost != null) {
            Column(
                Modifier.fillMaxWidth().padding(top = spacing.s8),
                verticalArrangement = Arrangement.spacedBy(spacing.s8),
            ) {
                hero.primary?.let {
                    HlButton(stringResource(actionLabel(it)), { onAction(it) }, Modifier.fillMaxWidth(), brand = true)
                }
                hero.ghost?.let { HlSecondaryButton(stringResource(actionLabel(it)), { onAction(it) }) }
            }
        }
    }
}

/** The CarPlay mark in the signal ring: lit by step while starting, whole when running, danger when it needs you. */
@Composable
private fun HeroDial(
    tone: SignalTone,
    progress: Float?,
) {
    val colors = HlTheme.colors
    val motion = HlTheme.motion
    val color by animateColorAsState(
        when (tone) {
            SignalTone.ERROR -> colors.danger
            SignalTone.IDLE -> colors.inkMuted
            SignalTone.PROGRESS, SignalTone.ON -> colors.brand
        },
        motion.fade(),
    )
    val lit by animateFloatAsState(progress ?: 1f, motion.fade())
    Box(contentAlignment = Alignment.Center) {
        SignalRing(color, colors.track, colors.raisedLo, progress = lit, size = HlTheme.sizes.gauge)
        HlIcon(HlIcons.CarPlay, if (tone == SignalTone.IDLE) colors.ink else color, size = HlTheme.sizes.icon * 2)
    }
}

/**
 * The hero's state tag: running and starting as the brand [HlTag]; needs you in the danger colour; idle states quiet.
 * Night tints, Bento fills (docs/LESSONS.md 31).
 */
@Composable
private fun StateTag(
    text: String,
    tone: SignalTone,
    modifier: Modifier = Modifier,
) {
    if (tone == SignalTone.ON || tone == SignalTone.PROGRESS) {
        HlTag(text, modifier)
        return
    }
    val colors = HlTheme.colors
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val danger = tone == SignalTone.ERROR
    val (fill, edge, ink) =
        when {
            danger && night -> {
                Triple(
                    colors.danger.copy(alpha = TAG_FILL),
                    colors.danger.copy(alpha = TAG_EDGE),
                    colors.danger,
                )
            }

            danger -> {
                Triple(colors.danger, Color.Transparent, colors.inkInverse)
            }

            else -> {
                Triple(colors.raisedLo, colors.line, colors.inkMuted)
            }
        }
    Box(
        modifier
            .background(fill, CircleShape)
            .border(HlTheme.strokes.hairline, edge, CircleShape)
            .padding(horizontal = HlTheme.spacing.s8, vertical = HlTheme.spacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        HlText(text.uppercase(), HlTheme.typography.caps.copy(fontWeight = FontWeight.SemiBold), ink, maxLines = 1)
    }
}

/** The iPhone, the Link and the beta switch, in one group. */
@Composable
private fun StatusList(
    state: CarPlayUiState,
    onToggle: () -> Unit,
    onIphone: () -> Unit,
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
        )
        HlGroupDivider()
        HlValueRow(
            value = stringResource(R.string.carplay_link_row),
            hint = stringResource(state.link.hint),
            detail = stringResource(state.link.detail),
            container = Color.Transparent,
            hintColor = if (state.link.live) colors.success else Color.Unspecified,
        )
        HlGroupDivider()
        val switchState = stringResource(if (state.enabled) R.string.carplay_on else R.string.carplay_off)
        HlValueRow(
            value = stringResource(R.string.carplay_switch),
            hint = switchState,
            modifier = Modifier.semantics { stateDescription = switchState },
            detail = stringResource(R.string.carplay_switch_detail),
            onClick = onToggle,
            container = Color.Transparent,
            // A live state, as rows mark one ("Connected" on Add device); brand lime would vanish on Bento
            // (docs/LESSONS.md 31).
            hintColor = if (state.enabled) colors.success else Color.Unspecified,
        )
    }
}

@StringRes
private fun actionLabel(action: TabAction): Int =
    when (action) {
        TabAction.ADD_IPHONE -> R.string.carplay_action_add
        TabAction.CHOOSE_IPHONE -> R.string.carplay_action_choose
        TabAction.TURN_ON_BETA -> R.string.carplay_action_beta
        TabAction.START_CARPLAY -> R.string.carplay_action_start
        TabAction.OPEN_CARPLAY -> R.string.carplay_open
        TabAction.CANCEL -> R.string.cancel
        TabAction.DISCONNECT -> R.string.carplay_action_disconnect
        TabAction.ALLOW -> R.string.carplay_action_allow
        TabAction.TURN_ON_WIFI -> R.string.carplay_action_wifi
        TabAction.TURN_ON_BLUETOOTH -> R.string.carplay_action_bluetooth
    }

/** The danger tag's faint fill and edge on Night, as HlTag's brand ones (Paper: 12 % and 28 %). */
private const val TAG_FILL = 0.12f
private const val TAG_EDGE = 0.28f

/** Sample states for previews and screenshot tests (Paper "Tab 1–4"). */
internal val PreviewIphone = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
private val PreviewLine = CarPlayLine(R.string.carplay_status_connected, tone = SignalTone.ON)

internal val PreviewCarPlayNotSetUp =
    carPlayTab(true, CarPlayStatus.Engine(CarPlayState.Idle), emptyList(), null, PreviewLine)

internal val PreviewCarPlayConnecting =
    carPlayTab(
        true,
        CarPlayStatus.Engine(CarPlayState.Connecting(ConnectStep.IDENTIFYING)),
        listOf(PreviewIphone),
        null,
        PreviewLine,
    )

internal val PreviewCarPlayConnected =
    carPlayTab(
        true,
        CarPlayStatus.Engine(CarPlayState.Connected("phone", Instant.EPOCH)),
        listOf(PreviewIphone),
        PreviewIphone.address,
        PreviewLine,
    )

internal val PreviewCarPlayWifiOff =
    carPlayTab(
        true,
        CarPlayStatus.Engine(CarPlayState.Recovering(CarPlayError.WifiOff, 1, Instant.EPOCH)),
        listOf(PreviewIphone),
        null,
        PreviewLine,
    )

/** Home's CarPlay line while our receiver connects. */
internal val PreviewCarPlayConnectingLine =
    carPlayLine(CarPlayStatus.Engine(CarPlayState.Connecting(ConnectStep.DIALING_PHONE)), Instant.EPOCH)

internal val PreviewCarPlayOff = carPlayTab(false, CarPlayStatus.Off, listOf(PreviewIphone), null, PreviewLine)

@Preview(name = "CarPlay · Night", widthDp = 411, heightDp = 856)
@Composable
private fun CarPlayNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { CarPlayScreen(PreviewCarPlayConnected, {}, {}, {}, PaddingValues()) }
    }
}

@Preview(name = "CarPlay · Bento", widthDp = 411, heightDp = 856)
@Composable
private fun CarPlayBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) { CarPlayScreen(PreviewCarPlayWifiOff, {}, {}, {}, PaddingValues()) }
    }
}

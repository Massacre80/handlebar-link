package com.abid.handlebarlink.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.AddDeviceSlot
import com.abid.handlebarlink.core.designsystem.component.ArcGauge
import com.abid.handlebarlink.core.designsystem.component.DeviceRow
import com.abid.handlebarlink.core.designsystem.component.DeviceTile
import com.abid.handlebarlink.core.designsystem.component.HlAppHeaderDefaults
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlCard
import com.abid.handlebarlink.core.designsystem.component.HlIconButton
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.SignalCard
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.designsystem.component.StatusPill
import com.abid.handlebarlink.core.designsystem.component.TickBar
import com.abid.handlebarlink.core.designsystem.component.TickRing
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.carplay.BikePlayCard
import com.abid.handlebarlink.ui.carplay.BikePlayCardState
import com.abid.handlebarlink.ui.carplay.PreviewBikePlayConnected
import com.abid.handlebarlink.ui.carplay.TabAction
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout

/**
 * Home in the rider's look: one column upright, two panes on its side ([WindowLayout.PANES]: the bike, the cards).
 * [contentPadding]: room for the app header (the wordmark, the link pill and Settings live there, on every tab) and
 * the floating tab bar or rail.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    contentPadding: PaddingValues,
    onAddDevice: () -> Unit = {},
    onDevice: () -> Unit = {},
    onHandlebar: () -> Unit = {},
    onBikePlay: (TabAction) -> Unit = {},
    onBikePlayTab: () -> Unit = {},
) {
    val actions = HomeActions(onAddDevice, onDevice, onHandlebar, onBikePlay, onBikePlayTab)
    val panes = LocalWindowLayout.current == WindowLayout.PANES
    when {
        panes && HlTheme.variant == HlThemeVariant.NIGHT -> HomeNightPanes(state, actions, contentPadding)
        panes -> HomeBentoPanes(state, actions, contentPadding)
        HlTheme.variant == HlThemeVariant.NIGHT -> HomeNight(state, actions, contentPadding)
        else -> HomeBento(state, actions, contentPadding)
    }
}

@Composable
internal fun Caps(
    @StringRes text: Int,
) {
    HlText(stringResource(text).uppercase(), HlTheme.typography.caps, HlTheme.colors.inkMuted, maxLines = 1)
}

/** The BikePlay card (design B "Instrument"): its button acts as the tab's; a tap elsewhere opens the BikePlay tab. */
@Composable
internal fun HomeBikePlayCard(
    state: BikePlayCardState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    BikePlayCard(state, actions.onBikePlay, actions.onBikePlayTab, modifier)
}

/** The pill's text for [status]. */
@StringRes
internal fun pillText(status: LinkStatus): Int =
    when (status) {
        LinkStatus.NO_BIKE -> R.string.pill_no_bike
        LinkStatus.NOT_RIDING -> R.string.pill_not_riding
        LinkStatus.LOOKING -> R.string.pill_looking
        LinkStatus.CONNECTING -> R.string.pill_connecting
        LinkStatus.CONNECTED -> R.string.pill_connected
        LinkStatus.RECONNECTING -> R.string.pill_reconnecting
        LinkStatus.BLUETOOTH_OFF -> R.string.pill_bluetooth_off
        LinkStatus.NEEDS_PERMISSION -> R.string.pill_permission
        LinkStatus.NEEDS_PAIRING -> R.string.pill_pairing
    }

/** Previews leave the same room for the header and the tab bar as the app does (without system bars). */
@Composable
private fun previewBarSpace() =
    PaddingValues(
        top = HlAppHeaderDefaults.height(compact = false),
        bottom = HlTheme.sizes.tabBar + HlTheme.spacing.s20 * 2,
    )

@Preview(name = "Home · Night", widthDp = 411, heightDp = 856)
@Composable
private fun HomeNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) {
            HomeScreen(
                HomeUiState(LinkStatus.CONNECTED, bikePlay = PreviewBikePlayConnected),
                contentPadding = previewBarSpace(),
            )
        }
    }
}

@Preview(name = "Home · Night · landscape", widthDp = 817, heightDp = 393)
@Composable
private fun HomeNightLandscapePreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        CompositionLocalProvider(LocalWindowLayout provides WindowLayout.PANES) {
            HlBackground(Modifier.fillMaxSize()) {
                HomeScreen(
                    HomeUiState(LinkStatus.CONNECTED, bikePlay = PreviewBikePlayConnected),
                    contentPadding =
                        PaddingValues(
                            top = HlAppHeaderDefaults.height(compact = true),
                            end = HlTheme.sizes.tabRail + HlTheme.spacing.s16 * 2,
                        ),
                )
            }
        }
    }
}

@Preview(name = "Home · Bento", widthDp = 411, heightDp = 856)
@Composable
private fun HomeBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) {
            HomeScreen(HomeUiState(LinkStatus.LOOKING), contentPadding = previewBarSpace())
        }
    }
}

/** What Home can open. */
internal data class HomeActions(
    val onAddDevice: () -> Unit,
    val onDevice: () -> Unit,
    val onHandlebar: () -> Unit,
    /** A BikePlay card button. */
    val onBikePlay: (TabAction) -> Unit = {},
    /** The BikePlay card outside its button: the BikePlay tab. */
    val onBikePlayTab: () -> Unit = {},
)

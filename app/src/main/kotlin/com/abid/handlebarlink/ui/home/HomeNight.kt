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

/** "Home 3 — Night": the bike as the hero between the gauges and the cards. */
@Composable
internal fun HomeNight(
    state: HomeUiState,
    onSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(contentPadding),
    ) {
        NightHeader(state, onSettings)
        NightGauges()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HeroBike(Modifier.align(Alignment.TopCenter))
            Row(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = spacing.s16),
                horizontalArrangement = Arrangement.spacedBy(spacing.s10),
            ) {
                DeviceTile(
                    stringResource(R.string.intercom),
                    stringResource(R.string.intercom_none),
                    stringResource(R.string.intercom_none_detail),
                    connected = null,
                    modifier = Modifier.weight(NIGHT_TILE_WEIGHT),
                )
                AddDeviceSlot(
                    stringResource(R.string.add_device),
                    stringResource(R.string.coming_soon),
                    Modifier.weight(1f),
                )
            }
        }
        CarPlayCard(Modifier.padding(spacing.s16))
    }
}

/** The bike's wordmark and the link pill, centred; settings top right. */
@Composable
private fun NightHeader(
    state: HomeUiState,
    onSettings: () -> Unit,
) {
    val spacing = HlTheme.spacing
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = spacing.s18),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            HlText(stringResource(R.string.bike_model), HlTheme.typography.wordmark, HlTheme.colors.ink)
            Pill(state.link)
        }
        HlIconButton(
            HlIcons.Settings,
            stringResource(R.string.settings),
            onSettings,
            Modifier.align(Alignment.TopEnd).padding(top = spacing.s8, end = spacing.s12),
        )
    }
}

/** Service and trip gauges: empty until the Garage and ride logging exist. */
@Composable
private fun NightGauges() {
    val spacing = HlTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(top = spacing.s22, start = spacing.s16, end = spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(spacing.s28, Alignment.CenterHorizontally),
    ) {
        ArcGauge(
            stringResource(R.string.no_value),
            stringResource(R.string.gauge_service),
            progress = null,
            color = HlTheme.colors.gauge,
            modifier = Modifier.weight(1f, fill = false),
            overline = stringResource(R.string.no_data),
        )
        ArcGauge(
            stringResource(R.string.no_value),
            stringResource(R.string.gauge_trip),
            progress = null,
            color = HlTheme.colors.brand,
            modifier = Modifier.weight(1f, fill = false),
            overline = stringResource(R.string.no_data),
        )
    }
}

/** The bike render, wider than the screen like the artboard, fading out towards the cards. */
@Composable
private fun HeroBike(modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Image(
            painterResource(R.drawable.bike_cb350rs),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .requiredWidth(maxWidth * HERO_OVERSCAN)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(HERO_FADE_START to Color.Black, HERO_FADE_END to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
        )
    }
}

// The artboard's ratios: the render is 560 of 411 wide and fades from 52 % to 86 % of its height;
// the tiles are 186:168.
private const val HERO_OVERSCAN = 1.36f
private const val HERO_FADE_START = 0.52f
private const val HERO_FADE_END = 0.86f
private const val NIGHT_TILE_WEIGHT = 1.1f

package com.abid.handlebarlink.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
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
import com.abid.handlebarlink.core.designsystem.component.CardRow
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
import com.abid.handlebarlink.core.designsystem.component.groundShadow
import com.abid.handlebarlink.core.designsystem.theme.BikeGroundShadow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/** "Home 4 — Bento": everything in cards, scrolling when the screen is short. */
@Composable
internal fun HomeBento(
    state: HomeUiState,
    onSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    // The page scrolls full width; wide windows centre a column of at most HlSizes.contentMax.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = HlTheme.sizes.contentMax)
                .fillMaxWidth()
                .padding(horizontal = spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = spacing.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(state.link)
                Spacer(Modifier.weight(1f))
                HlIconButton(HlIcons.Settings, stringResource(R.string.settings), onSettings)
            }
            BentoBikeCard()
            BentoStats()
            CarPlayCard()
            CardRow {
                DeviceRow(
                    stringResource(R.string.intercom_none),
                    stringResource(R.string.intercom_none_detail),
                    connected = null,
                    modifier = Modifier.weight(BENTO_DEVICE_WEIGHT).fillMaxHeight(),
                )
                // Bento's slot has no second line in the artboard (it would not fit its narrow column).
                AddDeviceSlot(
                    stringResource(R.string.add_device_short),
                    detail = null,
                    Modifier.weight(BENTO_ADD_WEIGHT).fillMaxHeight(),
                )
            }
            Spacer(Modifier.height(spacing.s8))
        }
    }
}

/** The bike card: name and details, the render, the odometer (empty until the Garage exists). */
@Composable
private fun BentoBikeCard() {
    val spacing = HlTheme.spacing
    val colors = HlTheme.colors
    val type = HlTheme.typography
    HlCard(Modifier.fillMaxWidth().height(HlTheme.sizes.heroCard)) {
        // The bike stands on the design's ground shadow ("Home 2 — Minimal"): tyre contact shadows and a
        // soft ambient one, scaled with the render and lifted off the card's edge so they stay visible.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = spacing.s12)
                .fillMaxWidth(BENTO_BIKE_WIDTH)
                .aspectRatio(BikeGroundShadow.boxWidth / BikeGroundShadow.boxHeight)
                .groundShadow(BikeGroundShadow, colors.ink),
        ) {
            Image(
                painterResource(R.drawable.bike_cb350rs),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.padding(spacing.s16), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            HlText(stringResource(R.string.bike_full_name), type.lead, colors.ink)
            HlText(stringResource(R.string.bike_details), type.label, colors.inkMuted)
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(spacing.s16),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Caps(R.string.odometer)
            HlText(stringResource(R.string.no_value), type.figureSmall, colors.ink)
        }
    }
}

/** Today's distance and the next service: empty until ride logging and the Garage exist. */
@Composable
private fun BentoStats() {
    val spacing = HlTheme.spacing
    val colors = HlTheme.colors
    val type = HlTheme.typography
    Row(
        // The design height is a minimum (large text); both cards take the taller one's height.
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = HlTheme.sizes.statCard),
        horizontalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        HlCard(Modifier.weight(1f).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(spacing.s16),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.s10, Alignment.CenterVertically),
            ) {
                TickRing(stringResource(R.string.no_value), stringResource(R.string.km_today), progress = null)
                HlText(stringResource(R.string.no_rides), type.label, colors.inkMuted)
            }
        }
        HlCard(Modifier.weight(1f).fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(spacing.s16), verticalArrangement = Arrangement.SpaceBetween) {
                Caps(R.string.next_service)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.s12)) {
                    HlText(stringResource(R.string.no_value), type.figureLarge, colors.ink)
                    TickBar(progress = null)
                    HlText(stringResource(R.string.service_not_set), type.meta, colors.inkMuted)
                }
            }
        }
    }
}

// The artboard's ratios: the bike fills 290 of 379, the device row is 1.25:0.75.
private const val BENTO_BIKE_WIDTH = 0.77f
private const val BENTO_DEVICE_WEIGHT = 1.25f
private const val BENTO_ADD_WEIGHT = 0.75f

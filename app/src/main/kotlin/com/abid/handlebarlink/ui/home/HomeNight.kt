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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import com.abid.handlebarlink.core.designsystem.component.Backdrop
import com.abid.handlebarlink.core.designsystem.component.CardRow
import com.abid.handlebarlink.core.designsystem.component.DeviceRow
import com.abid.handlebarlink.core.designsystem.component.DeviceTile
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlBatteryLevel
import com.abid.handlebarlink.core.designsystem.component.HlCard
import com.abid.handlebarlink.core.designsystem.component.HlIconButton
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.SignalCard
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.designsystem.component.StatusPill
import com.abid.handlebarlink.core.designsystem.component.TickBar
import com.abid.handlebarlink.core.designsystem.component.TickRing
import com.abid.handlebarlink.core.designsystem.component.backdropSource
import com.abid.handlebarlink.core.designsystem.component.rememberBackdrop
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.BikeImage
import com.abid.handlebarlink.ui.devices.ADD_DEVICE
import com.abid.handlebarlink.ui.devices.kindIcon
import com.abid.handlebarlink.ui.devices.kindName
import com.abid.handlebarlink.ui.frame.pageTop
import com.abid.handlebarlink.ui.frame.rememberPageScrollState
import com.abid.handlebarlink.ui.handlebar.HandlebarCard
import com.abid.handlebarlink.ui.navigation.containerContent
import com.abid.handlebarlink.ui.navigation.containerTransform
import com.abid.handlebarlink.ui.navigation.rememberContainerTransform

/**
 * "Home 3 — Night": the bike as the hero between the gauges and the cards. The bike area keeps the
 * artboard's share of the screen height but never less than the tiles need; when the screen is too short
 * the page scrolls, and wide windows centre a column of at most [HlSizes.contentMax].
 */
@Composable
internal fun HomeNight(
    state: HomeUiState,
    actions: HomeActions,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    val column = Modifier.widthIn(max = HlTheme.sizes.contentMax).fillMaxWidth()
    val backdrop = rememberBackdrop()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The tiles start where the artboard puts them on the bike (64 % down the render), so its wheels
        // sit behind the glass on every screen; the render's height follows the column's width.
        val columnWidth = minOf(maxWidth, HlTheme.sizes.contentMax)
        val bikeHeight = columnWidth * HERO_OVERSCAN * RENDER_ASPECT
        // Capped at the artboard's share of the height less the navigation bar: the artboard has none, and on
        // the Poco the CarPlay card slid under the tab bar by about 29 dp (seen on the phone, 2026-11-17).
        val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val heroHeight =
            minOf(bikeHeight * TILES_AT + HlTheme.sizes.tile, maxHeight * HERO_MAX_SHARE - navigationBar)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberPageScrollState())
                .heightIn(min = maxHeight)
                .windowInsetsPadding(WindowInsets.pageTop)
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Under the app header (its 12 dp below the pill included): the gauges 22 dp under the pill, or without
            // them the bike 48 dp under it, clear of the header (its mirrors covered the status pill on the phone,
            // owner 2026-11-18).
            if (state.rideData) {
                NightGauges(column.padding(top = spacing.s10))
            } else {
                Spacer(Modifier.height(spacing.s24 + spacing.s12))
            }
            Box(column.height(heroHeight)) {
                HeroBike(backdrop, Modifier.align(Alignment.TopCenter))
                NightDevices(
                    state.device,
                    backdrop,
                    actions.onAddDevice,
                    actions.onDevice,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = spacing.s16),
                )
            }
            // Paper "BikePlay card B — Instrument, Home (Night)": the BikePlay card 10 dp under the tiles, the
            // Handlebar card 10 dp under it.
            HomeBikePlayCard(
                state.bikePlay,
                actions,
                column.padding(horizontal = spacing.s16).padding(top = spacing.s10),
            )
            HandlebarCard(
                state.handlebar,
                actions.onHandlebar,
                column.padding(horizontal = spacing.s16).padding(top = spacing.s10, bottom = spacing.s16),
            )
            // Spare height on tall screens goes here, under the cards.
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The device tile (the device's page, or Add device when none) and the Add device slot. */
@Composable
internal fun NightDevices(
    device: HomeDevice?,
    backdrop: Backdrop?,
    onAddDevice: () -> Unit,
    onDevice: () -> Unit,
    modifier: Modifier,
) {
    CardRow(modifier) {
        DeviceTile(
            stringResource(device?.let { kindName(it.kind) } ?: R.string.intercom),
            device?.name ?: stringResource(R.string.intercom_none),
            nightDeviceDetail(device),
            connected = device?.connected,
            modifier = Modifier.weight(NIGHT_TILE_WEIGHT).fillMaxHeight(),
            battery =
                device?.battery?.let {
                    HlBatteryLevel(
                        it,
                        stringResource(R.string.device_battery, it),
                    )
                },
            backdrop = backdrop,
            icon = device?.let { kindIcon(it.kind) } ?: HlIcons.Intercom,
            // The device's page; with none added, Add device.
            onClick = if (device != null) onDevice else onAddDevice,
        )
        // Grows into the Add device page; the slot keeps its own size inside (no stretching).
        val addDevice = rememberContainerTransform(ADD_DEVICE, expanded = false)
        Box(Modifier.weight(1f).containerTransform(addDevice).fillMaxHeight()) {
            AddDeviceSlot(
                stringResource(R.string.add_device),
                stringResource(R.string.add_device_detail),
                Modifier.containerContent(addDevice).fillMaxSize(),
                onClick = onAddDevice,
                backdrop = backdrop,
            )
        }
    }
}

/** Service and trip gauges: shown only for a meter that reports speed and odometer (rideData), empty until then. */
@Composable
internal fun NightGauges(modifier: Modifier) {
    val spacing = HlTheme.spacing
    Row(
        modifier.padding(start = spacing.s16, end = spacing.s16),
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

/**
 * The bike render, wider than the screen like the artboard, fading out towards the cards; recorded into
 * [backdrop] for the intercom tile's glass.
 */
@Composable
private fun HeroBike(
    backdrop: Backdrop,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BikeImage(
            Modifier
                .requiredWidth(maxWidth * HERO_OVERSCAN)
                .aspectRatio(1f / RENDER_ASPECT)
                .backdropSource(backdrop)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                // The fade's brush is built once per size, not on every frame (drawWithCache).
                .drawWithCache {
                    val fade =
                        Brush.verticalGradient(
                            HERO_FADE_START to Color.Black,
                            HERO_FADE_END to Color.Transparent,
                        )
                    onDrawWithContent {
                        drawContent()
                        drawRect(fade, blendMode = BlendMode.DstIn)
                    }
                },
        )
    }
}

// The artboard's ratios: the render is 560 × 409 and 560 of 411 wide, fades from 52 % to 86 % of its
// height, and the tiles (186:168) start 263 dp below its top (64 %).
private const val RENDER_ASPECT = 409f / 560f
private const val TILES_AT = 263f / 409f

/** The hero (bike and tiles) spans 386 of the artboard's 856 dp at most. */
private const val HERO_MAX_SHARE = 386f / 856f
private const val HERO_OVERSCAN = 1.36f
private const val HERO_FADE_START = 0.52f
private const val HERO_FADE_END = 0.86f
private const val NIGHT_TILE_WEIGHT = 1.1f

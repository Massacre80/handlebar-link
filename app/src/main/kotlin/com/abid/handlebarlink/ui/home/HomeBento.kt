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
import androidx.compose.foundation.layout.width
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
import com.abid.handlebarlink.ui.BikeImage
import com.abid.handlebarlink.ui.devices.ADD_DEVICE
import com.abid.handlebarlink.ui.frame.pageTop
import com.abid.handlebarlink.ui.handlebar.HandlebarCard
import com.abid.handlebarlink.ui.navigation.containerContent
import com.abid.handlebarlink.ui.navigation.containerTransform
import com.abid.handlebarlink.ui.navigation.rememberContainerTransform

/** "Home 4 — Bento": everything in cards, scrolling when the screen is short. */
@Composable
internal fun HomeBento(
    state: HomeUiState,
    actions: HomeActions,
    contentPadding: PaddingValues,
) {
    val spacing = HlTheme.spacing
    // The page scrolls full width; wide windows centre a column of at most HlSizes.contentMax.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.pageTop)
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
            BentoTopRow(state.link, actions.onSettings)
            BentoBikeCard(state.rideData)
            if (state.rideData) BentoStats()
            CarPlayCard(state.carPlay)
            BentoDevices(state.device, actions)
            HandlebarCard(state.handlebar, actions.onHandlebar)
            Spacer(Modifier.height(spacing.s8))
        }
    }
}

/** The link pill and settings, above the bike card. */
@Composable
internal fun BentoTopRow(
    link: LinkStatus,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = HlTheme.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(link)
        Spacer(Modifier.weight(1f))
        HlIconButton(HlIcons.Settings, stringResource(R.string.settings), onSettings)
    }
}

/** The device row (the device's page, or Add device when none) and the Add device slot. */
@Composable
internal fun BentoDevices(
    device: HomeDevice?,
    actions: HomeActions,
) {
    CardRow {
        DeviceRow(
            device?.name ?: stringResource(R.string.intercom_none),
            bentoDeviceDetail(device),
            connected = device?.connected,
            modifier = Modifier.weight(BENTO_DEVICE_WEIGHT).fillMaxHeight(),
            // The device's page; with none added, Add device.
            onClick = if (device != null) actions.onDevice else actions.onAddDevice,
        )
        // Bento's slot has no second line in the artboard (it would not fit its narrow column).
        // Grows into the Add device page; the slot keeps its own size inside (no stretching).
        val addDevice = rememberContainerTransform(ADD_DEVICE, expanded = false)
        Box(Modifier.weight(BENTO_ADD_WEIGHT).containerTransform(addDevice).fillMaxHeight()) {
            AddDeviceSlot(
                stringResource(R.string.add_device_short),
                detail = null,
                Modifier.containerContent(addDevice).fillMaxSize(),
                onClick = actions.onAddDevice,
            )
        }
    }
}

/**
 * The bike card: name and details, the render, and the odometer once the meter reports it ([rideData]). Upright it
 * has the design's height; [fitted] (landscape) it fills [modifier]'s height and the bike shrinks to the room under
 * the name, so the two never overlap.
 */
@Composable
internal fun BentoBikeCard(
    rideData: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().height(HlTheme.sizes.heroCard),
    fitted: Boolean = false,
) {
    val spacing = HlTheme.spacing
    val colors = HlTheme.colors
    val type = HlTheme.typography
    HlCard(modifier) {
        val name: @Composable () -> Unit = {
            Column(Modifier.padding(spacing.s16), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                HlText(stringResource(R.string.bike_full_name), type.lead, colors.ink)
                HlText(stringResource(R.string.bike_details), type.label, colors.inkMuted)
            }
        }
        if (fitted) {
            Column(Modifier.fillMaxSize()) {
                name()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                    val width = minOf(maxWidth * BENTO_BIKE_WIDTH, (maxHeight - spacing.s12) * GROUND_RATIO)
                    BentoBike(Modifier, Modifier.width(width))
                }
            }
        } else {
            BentoBike(Modifier.align(Alignment.BottomEnd), Modifier.fillMaxWidth(BENTO_BIKE_WIDTH))
            name()
        }
        if (rideData) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(spacing.s16),
                verticalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                Caps(R.string.odometer)
                HlText(stringResource(R.string.no_value), type.figureSmall, colors.ink)
            }
        }
    }
}

/**
 * The bike on the design's ground shadow ("Home 2 — Minimal"): tyre contact shadows and a soft ambient one, scaled
 * with the render and lifted off the card's edge so they stay visible; [width] sizes it.
 */
@Composable
private fun BentoBike(
    modifier: Modifier,
    width: Modifier,
) {
    Box(
        modifier
            .padding(bottom = HlTheme.spacing.s12)
            .then(width)
            .aspectRatio(GROUND_RATIO)
            .groundShadow(BikeGroundShadow, HlTheme.colors.ink),
    ) {
        BikeImage(Modifier.fillMaxSize())
    }
}

/** Today's distance and the next service: empty until ride logging and the Garage exist. */
@Composable
internal fun BentoStats() {
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

/** The ground shadow's box, which the render fills. */
private val GROUND_RATIO = BikeGroundShadow.boxWidth / BikeGroundShadow.boxHeight

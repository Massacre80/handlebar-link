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

/** Home in the rider's look. [contentPadding]: room for the floating tab bar. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    if (HlTheme.variant == HlThemeVariant.NIGHT) {
        HomeNight(state, onSettings, contentPadding)
    } else {
        HomeBento(state, onSettings, contentPadding)
    }
}

@Composable
internal fun Caps(
    @StringRes text: Int,
) {
    HlText(stringResource(text).uppercase(), HlTheme.typography.caps, HlTheme.colors.inkMuted, maxLines = 1)
}

@Composable
internal fun Pill(status: LinkStatus) {
    StatusPill(stringResource(pillText(status)), on = status == LinkStatus.CONNECTED)
}

/** CarPlay has no source of its own yet (CarLink runs it): an idle card, no button. */
@Composable
internal fun CarPlayCard(modifier: Modifier = Modifier) {
    SignalCard(
        stringResource(R.string.carplay_title),
        stringResource(R.string.carplay_interim),
        SignalTone.IDLE,
        modifier,
    )
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

/** Previews leave the same room for the tab bar as the app does (without a navigation bar). */
@Composable
private fun previewBarSpace() = PaddingValues(bottom = HlTheme.sizes.tabBar + HlTheme.spacing.s20 * 2)

@Preview(name = "Home · Night", widthDp = 411, heightDp = 856)
@Composable
private fun HomeNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) {
            HomeScreen(HomeUiState(LinkStatus.CONNECTED), onSettings = {}, contentPadding = previewBarSpace())
        }
    }
}

@Preview(name = "Home · Bento", widthDp = 411, heightDp = 856)
@Composable
private fun HomeBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) {
            HomeScreen(HomeUiState(LinkStatus.LOOKING), onSettings = {}, contentPadding = previewBarSpace())
        }
    }
}

package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

// Previews of every component in both looks, laid out like the artboards (411 dp wide) so they can be
// compared with the design. UI.4 turns them into screenshot tests.

@Composable
internal fun Gallery(
    variant: HlThemeVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    HlTheme(variant) {
        HlBackground {
            Column(
                Modifier.fillMaxWidth().padding(HlTheme.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s16),
                content = content,
            )
        }
    }
}

@Composable
internal fun Pills() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        StatusPill("Connected", on = true)
        StatusPill("Looking for bike", on = false)
    }
}

@Composable
internal fun Gauges() {
    if (HlTheme.variant == HlThemeVariant.NIGHT) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s28, Alignment.CenterHorizontally),
        ) {
            ArcGauge(
                "420 km",
                "To next service",
                SAMPLE_SERVICE,
                HlTheme.colors.gauge,
                Modifier.weight(1f),
                overline = "12,480 km",
            )
            ArcGauge("—", "Trip today", null, HlTheme.colors.brand, Modifier.weight(1f), overline = "No data yet")
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
            HlCard(Modifier.weight(1f)) {
                Column(Modifier.padding(HlTheme.spacing.s16), horizontalAlignment = Alignment.CenterHorizontally) {
                    TickRing("23.4", "km today", SAMPLE_TRIP)
                }
            }
            HlCard(Modifier.weight(1f)) {
                Column(
                    Modifier.padding(HlTheme.spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12),
                ) {
                    HlText("420 km left", HlTheme.typography.lead, HlTheme.colors.ink)
                    TickBar(SAMPLE_SERVICE)
                    TickBar(null)
                }
            }
        }
    }
}

@Composable
internal fun Devices() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s10)) {
        if (HlTheme.variant == HlThemeVariant.NIGHT) {
            DeviceTile(
                "Intercom",
                "Helmet intercom",
                "EJEAS V6 Pro+",
                connected = true,
                Modifier.weight(NIGHT_TILE_WEIGHT),
                status = "72%",
            )
            AddDeviceSlot("Add device", "Intercom or headset", Modifier.weight(1f), onClick = {})
        } else {
            DeviceRow("Helmet intercom", "Intercom · 72%", connected = true, Modifier.weight(BENTO_DEVICE_WEIGHT))
            AddDeviceSlot("Add", null, Modifier.weight(BENTO_ADD_WEIGHT), onClick = {})
        }
    }
}

@Composable
internal fun CarPlayStates() {
    val offer = SignalAction("Connect", primary = true) {}
    SignalCard("Connect Abid's iPhone?", "CarPlay over Wi-Fi Direct", SignalTone.IDLE, action = offer)
    val cancel = SignalAction("Cancel", primary = false) {}
    SignalCard(
        "Starting CarPlay",
        "Step 3 of 4 · Linking",
        SignalTone.PROGRESS,
        progress = SAMPLE_STEP,
        action = cancel,
    )
    val back = SignalAction("Return", primary = true, chevron = true) {}
    SignalCard("CarPlay is on", "Abid's iPhone · Wi-Fi Direct", SignalTone.ON, action = back)
    val retry = SignalAction("Try again", primary = true) {}
    SignalCard("Couldn't connect", "Unlock iPhone and try again", SignalTone.ERROR, action = retry)
}

internal val PreviewTabs =
    listOf(
        HlTab(HlIcons.Home, "Home"),
        HlTab(HlIcons.Rides, "Rides"),
        HlTab(HlIcons.CarPlay, "CarPlay"),
        HlTab(HlIcons.Garage, "Garage"),
        HlTab(HlIcons.Intercom, "Intercom"),
    )

@Composable
private fun Everything() {
    Pills()
    Gauges()
    Devices()
    CarPlayStates()
    HlTabBar(PreviewTabs, selected = 0, onSelect = {})
    Row(horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        HlIconButton(HlIcons.Settings, "Settings", onClick = {})
    }
}

@Preview(name = "Night · components", widthDp = 411, heightDp = 1180)
@Composable
private fun NightComponentsPreview() {
    Gallery(HlThemeVariant.NIGHT) { Everything() }
}

@Preview(name = "Bento · components", widthDp = 411, heightDp = 1180)
@Composable
private fun BentoComponentsPreview() {
    Gallery(HlThemeVariant.BENTO) { Everything() }
}

// Sample values from the artboards: 86 % to the next service, 23.4 of 40 km, step 3 of 4; the tile widths
// are the artboards' 186:168 (Night) and 1.25:0.75 (Bento) flex ratios.
private const val SAMPLE_SERVICE = 0.86f
private const val SAMPLE_TRIP = 0.58f
private const val SAMPLE_STEP = 0.75f
private const val NIGHT_TILE_WEIGHT = 1.1f
private const val BENTO_DEVICE_WEIGHT = 1.25f
private const val BENTO_ADD_WEIGHT = 0.75f

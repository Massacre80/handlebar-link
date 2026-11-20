package com.abid.handlebarlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlTab
import com.abid.handlebarlink.core.designsystem.component.HlTabBar
import com.abid.handlebarlink.core.designsystem.component.HlTabRail
import com.abid.handlebarlink.core.designsystem.preview.Landscape
import com.abid.handlebarlink.core.designsystem.preview.LargeText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.garage.Colourway
import com.abid.handlebarlink.garage.PaperKind
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.garage.ColourwaySheet
import com.abid.handlebarlink.ui.garage.GarageActions
import com.abid.handlebarlink.ui.garage.GarageScreen
import com.abid.handlebarlink.ui.garage.GarageUiState
import com.abid.handlebarlink.ui.garage.LogServiceSheet
import com.abid.handlebarlink.ui.garage.PaperSheet
import com.abid.handlebarlink.ui.garage.PreviewGarageAllGood
import com.abid.handlebarlink.ui.garage.PreviewGarageEmpty
import com.abid.handlebarlink.ui.garage.PreviewGaragePucOverdue
import com.abid.handlebarlink.ui.garage.PreviewGarageServiceSoon
import com.abid.handlebarlink.ui.garage.ReminderSheet
import com.abid.handlebarlink.ui.garage.SheetFrame
import com.abid.handlebarlink.ui.navigation.GarageRoute
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import com.abid.handlebarlink.ui.navigation.tabIndex
import com.android.tools.screenshot.PreviewTest

// The Garage tab (Paper "Garage 1–4", "2L") in Night, upright with the floating tab bar and on its side with the rail,
// and its sheets (Paper "Garage 5", "6", "8"). Framed like AppRoot without its Hilt view models. References are
// recorded on Linux CI (docs/LESSONS.md 26).

@Composable
private fun Upright(state: GarageUiState) =
    HlTheme(HlThemeVariant.NIGHT) {
        val spacing = HlTheme.spacing
        HlBackground(Modifier.fillMaxSize()) {
            GarageScreen(state, GarageActions(), PaddingValues(bottom = HlTheme.sizes.tabBar + spacing.s20 * 2))
            HlTabBar(
                tabs = TOP_LEVEL_DESTINATIONS.map { HlTab(it.icon, stringResource(it.label)) },
                selected = tabIndex(GarageRoute),
                onSelect = {},
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s20)
                        .widthIn(max = HlTheme.sizes.contentMax),
            )
        }
    }

@Composable
private fun Sideways(state: GarageUiState) =
    HlTheme(HlThemeVariant.NIGHT) {
        val spacing = HlTheme.spacing
        CompositionLocalProvider(LocalWindowLayout provides WindowLayout.PANES) {
            HlBackground(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    GarageScreen(state, GarageActions(), PaddingValues(end = HlTheme.sizes.tabRail + spacing.s16 * 2))
                    HlTabRail(
                        tabs = TOP_LEVEL_DESTINATIONS.map { HlTab(it.icon, stringResource(it.label)) },
                        selected = tabIndex(GarageRoute),
                        onSelect = {},
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = spacing.s16),
                    )
                }
            }
        }
    }

@PreviewTest
@Preview(name = "411x856", widthDp = 411, heightDp = 856)
@LargeText
@Composable
fun GarageEmptyNight() = Upright(PreviewGarageEmpty)

@PreviewTest
@Preview(name = "411x856", widthDp = 411, heightDp = 856)
@Preview(name = "411x1720 whole page", widthDp = 411, heightDp = 1720)
@LargeText
@Composable
fun GarageAllGoodNight() = Upright(PreviewGarageAllGood)

@PreviewTest
@Preview(name = "411x856", widthDp = 411, heightDp = 856)
@Composable
fun GarageServiceSoonNight() = Upright(PreviewGarageServiceSoon)

@PreviewTest
@Preview(name = "411x856", widthDp = 411, heightDp = 856)
@Composable
fun GaragePucOverdueNight() = Upright(PreviewGaragePucOverdue)

@PreviewTest
@Landscape
@Composable
fun GarageAllGoodNightLandscape() = Sideways(PreviewGarageAllGood)

@PreviewTest
@Landscape
@Composable
fun GarageEmptyNightLandscape() = Sideways(PreviewGarageEmpty)

@PreviewTest
@Landscape
@Composable
fun GaragePucOverdueNightLandscape() = Sideways(PreviewGaragePucOverdue)

@PreviewTest
@Preview(name = "411x856", widthDp = 411, heightDp = 856)
@LargeText
@Composable
fun GarageLogServiceSheet() =
    SheetFrame {
        LogServiceSheet(
            PreviewGarageAllGood.today,
            PreviewGarageAllGood.data.service,
            PreviewGarageAllGood.status.latest?.km,
            onSave = {},
            onCancel = {},
        )
    }

@PreviewTest
@Preview(name = "411x856", widthDp = 411, heightDp = 856)
@Composable
fun GarageInsuranceSheet() =
    SheetFrame {
        PaperSheet(
            PaperKind.INSURANCE,
            PreviewGarageAllGood.data.papers[PaperKind.INSURANCE],
            PreviewGarageAllGood.today,
            { _, _ -> },
            {},
        )
    }

@PreviewTest
@Preview(name = "411x856", widthDp = 411, heightDp = 856)
@Composable
fun GarageColourwaySheet() = SheetFrame { ColourwaySheet(Colourway.MATTE_GREY, {}, {}) }

@PreviewTest
@Preview(name = "411x856", widthDp = 411, heightDp = 856)
@Composable
fun GarageReminderSheet() = SheetFrame { ReminderSheet(null, PreviewGarageAllGood.today, { _, _, _ -> }, null, {}) }

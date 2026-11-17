package com.abid.handlebarlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlTab
import com.abid.handlebarlink.core.designsystem.component.HlTabBar
import com.abid.handlebarlink.core.designsystem.preview.LargeText
import com.abid.handlebarlink.core.designsystem.preview.ScreenSizes
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.ui.home.HomeScreen
import com.abid.handlebarlink.ui.home.HomeUiState
import com.abid.handlebarlink.ui.home.LinkStatus
import com.abid.handlebarlink.ui.later.LaterScreen
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import com.abid.handlebarlink.ui.settings.ModeScreen
import com.abid.handlebarlink.ui.settings.NoActions
import com.abid.handlebarlink.ui.settings.PermissionsScreen
import com.abid.handlebarlink.ui.settings.PreviewAccess
import com.abid.handlebarlink.ui.settings.PreviewSettings
import com.abid.handlebarlink.ui.settings.SettingsScreen
import com.abid.handlebarlink.ui.settings.ThemeScreen
import com.android.tools.screenshot.PreviewTest

// Every page in both looks at the nine sizes and with large text (testing-setup skill, Step 8). Pages are
// framed like AppRoot (background, floating tab bar, room for it) without its Hilt view models.

@Composable
private fun TabFrame(
    variant: HlThemeVariant,
    selected: Int,
    page: @Composable (PaddingValues) -> Unit,
) {
    HlTheme(variant) {
        val spacing = HlTheme.spacing
        HlBackground(Modifier.fillMaxSize()) {
            page(PaddingValues(bottom = HlTheme.sizes.tabBar + spacing.s20 * 2))
            HlTabBar(
                tabs = TOP_LEVEL_DESTINATIONS.map { HlTab(it.icon, stringResource(it.label)) },
                selected = selected,
                onSelect = {},
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = spacing.s16, end = spacing.s16, bottom = spacing.s20)
                        .widthIn(max = HlTheme.sizes.contentMax),
            )
        }
    }
}

@Composable
private fun Home(variant: HlThemeVariant) =
    TabFrame(
        variant,
        selected = 0,
    ) { HomeScreen(HomeUiState(LinkStatus.CONNECTED), onSettings = {}, contentPadding = it) }

@Composable
private fun Garage(variant: HlThemeVariant) =
    TabFrame(variant, selected = 3) { LaterScreen(HlIcons.Garage, R.string.tab_garage, R.string.later_garage, it) }

@Composable
private fun Settings(variant: HlThemeVariant) =
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) {
            SettingsScreen(
                PreviewSettings.copy(theme = if (variant == HlThemeVariant.NIGHT) AppTheme.NIGHT else AppTheme.BENTO),
                PreviewAccess,
                NoActions,
            )
        }
    }

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun HomeNight() = Home(HlThemeVariant.NIGHT)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun HomeBento() = Home(HlThemeVariant.BENTO)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun EmptyTabNight() = Garage(HlThemeVariant.NIGHT)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun EmptyTabBento() = Garage(HlThemeVariant.BENTO)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun SettingsNight() = Settings(HlThemeVariant.NIGHT)

@PreviewTest
@ScreenSizes
@LargeText
@Composable
fun SettingsBento() = Settings(HlThemeVariant.BENTO)

private fun themeOf(variant: HlThemeVariant) = if (variant == HlThemeVariant.NIGHT) AppTheme.NIGHT else AppTheme.BENTO

@Composable
private fun Theme(variant: HlThemeVariant) =
    HlTheme(variant) { HlBackground(Modifier.fillMaxSize()) { ThemeScreen(themeOf(variant), {}, {}) } }

@Composable
private fun Mode(variant: HlThemeVariant) =
    HlTheme(variant) { HlBackground(Modifier.fillMaxSize()) { ModeScreen(themeOf(variant), {}) } }

@PreviewTest
@Artboard
@LargeText
@Composable
fun ThemeNight() = Theme(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun ThemeBento() = Theme(HlThemeVariant.BENTO)

@PreviewTest
@Artboard
@LargeText
@Composable
fun ModeNight() = Mode(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun ModeBento() = Mode(HlThemeVariant.BENTO)

@Composable
private fun Permissions(variant: HlThemeVariant) =
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) { PermissionsScreen(PreviewAccess, {}, {}) }
    }

@PreviewTest
@Artboard
@LargeText
@Composable
fun PermissionsNight() = Permissions(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@LargeText
@Composable
fun PermissionsBento() = Permissions(HlThemeVariant.BENTO)

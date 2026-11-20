package com.abid.handlebarlink.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.abid.handlebarlink.core.designsystem.preview.Landscape
import com.abid.handlebarlink.core.designsystem.preview.LargeText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.carplay.CarPlayScreen
import com.abid.handlebarlink.ui.carplay.PreviewCarPlayConnected
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.PreviewTabFrame
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.garage.GarageActions
import com.abid.handlebarlink.ui.garage.GarageScreen
import com.abid.handlebarlink.ui.garage.PreviewGarageAllGood
import com.abid.handlebarlink.ui.home.HomeScreen
import com.abid.handlebarlink.ui.home.HomeUiState
import com.abid.handlebarlink.ui.home.LinkStatus
import com.abid.handlebarlink.ui.intercom.IntercomScreen
import com.abid.handlebarlink.ui.intercom.IntercomUiState
import com.abid.handlebarlink.ui.intercom.NoIntercomActions
import com.abid.handlebarlink.ui.navigation.CarPlayRoute
import com.abid.handlebarlink.ui.navigation.GarageRoute
import com.abid.handlebarlink.ui.navigation.HomeRoute
import com.abid.handlebarlink.ui.navigation.IntercomRoute
import com.abid.handlebarlink.ui.navigation.tabIndex
import com.android.tools.screenshot.PreviewTest

// The app header on every tab (Paper "Component · App header", "Header · Home/BikePlay/Garage/Devices"): the same
// header in the same place over the four tab pages, in both looks, upright and on its side with the rail, at rest and
// lifted (its glass, as when a page has scrolled under it). References are recorded on Linux CI (docs/LESSONS.md 26).

/** The four tab pages, each under the header. */
private enum class Tab { HOME, BIKEPLAY, GARAGE, DEVICES }

@Composable
private fun Framed(
    variant: HlThemeVariant,
    tab: Tab,
    panes: Boolean = false,
    lifted: Boolean = false,
) = HlTheme(variant) {
    CompositionLocalProvider(LocalWindowLayout provides if (panes) WindowLayout.PANES else WindowLayout.COLUMN) {
        val selected =
            when (tab) {
                Tab.HOME -> tabIndex(HomeRoute)
                Tab.BIKEPLAY -> tabIndex(CarPlayRoute)
                Tab.GARAGE -> tabIndex(GarageRoute)
                Tab.DEVICES -> tabIndex(IntercomRoute)
            }
        PreviewTabFrame(selected, LinkStatus.LOOKING, lifted) { room -> Page(tab, room) }
    }
}

@Composable
private fun Page(
    tab: Tab,
    room: PaddingValues,
) = when (tab) {
    Tab.HOME -> HomeScreen(HomeUiState(LinkStatus.LOOKING), contentPadding = room)
    Tab.BIKEPLAY -> CarPlayScreen(PreviewCarPlayConnected, onAction = {}, onIphone = {}, contentPadding = room)
    Tab.GARAGE -> GarageScreen(PreviewGarageAllGood, GarageActions(), room)
    Tab.DEVICES -> IntercomScreen(IntercomUiState(), NoIntercomActions, room)
}

@PreviewTest
@Artboard
@LargeText
@Composable
fun HeaderHomeNight() = Framed(HlThemeVariant.NIGHT, Tab.HOME)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HeaderBikePlayNight() = Framed(HlThemeVariant.NIGHT, Tab.BIKEPLAY)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HeaderGarageNight() = Framed(HlThemeVariant.NIGHT, Tab.GARAGE)

@PreviewTest
@Artboard
@LargeText
@Composable
fun HeaderDevicesNight() = Framed(HlThemeVariant.NIGHT, Tab.DEVICES)

@PreviewTest
@Artboard
@Composable
fun HeaderBikePlayBento() = Framed(HlThemeVariant.BENTO, Tab.BIKEPLAY)

@PreviewTest
@Artboard
@Composable
fun HeaderDevicesBento() = Framed(HlThemeVariant.BENTO, Tab.DEVICES)

/** Lifted: the glass and its hairline, as when the page has scrolled under the header. */
@PreviewTest
@Artboard
@Composable
fun HeaderLiftedNight() = Framed(HlThemeVariant.NIGHT, Tab.BIKEPLAY, lifted = true)

@PreviewTest
@Artboard
@Composable
fun HeaderLiftedBento() = Framed(HlThemeVariant.BENTO, Tab.BIKEPLAY, lifted = true)

@PreviewTest
@Landscape
@Composable
fun HeaderBikePlayNightLandscape() = Framed(HlThemeVariant.NIGHT, Tab.BIKEPLAY, panes = true)

@PreviewTest
@Landscape
@Composable
fun HeaderDevicesNightLandscape() = Framed(HlThemeVariant.NIGHT, Tab.DEVICES, panes = true)

@PreviewTest
@Landscape
@Composable
fun HeaderGarageBentoLandscape() = Framed(HlThemeVariant.BENTO, Tab.GARAGE, panes = true)

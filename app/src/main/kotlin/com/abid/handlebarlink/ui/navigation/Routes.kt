package com.abid.handlebarlink.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import kotlinx.serialization.Serializable

/** Home: the start destination (Navigation principles, "fixed start destination"). */
@Serializable
data object HomeRoute : NavKey

/** Rides tab. */
@Serializable
data object RidesRoute : NavKey

/** CarPlay tab. */
@Serializable
data object CarPlayRoute : NavKey

/** Garage tab. */
@Serializable
data object GarageRoute : NavKey

/** Intercom tab. */
@Serializable
data object IntercomRoute : NavKey

/** Settings: opened from Home's gear, on Home's back stack; a full page without the tab bar. */
@Serializable
data object SettingsRoute : NavKey

/** Settings › Appearance: the theme, pushed over Settings on Home's back stack. */
@Serializable
data object AppearanceRoute : NavKey

/** A tab: its route, icon and label. */
data class TopLevelDestination(
    /** The tab's root route; each tab keeps its own back stack. */
    val route: NavKey,
    /** Its icon (an HlIcons entry). */
    @param:DrawableRes val icon: Int,
    /** Its label. */
    @param:StringRes val label: Int,
)

/** The tabs, in the design's order ("Component · Tab bar"). Home first: it is the start destination. */
val TOP_LEVEL_DESTINATIONS: List<TopLevelDestination> =
    listOf(
        TopLevelDestination(HomeRoute, HlIcons.Home, R.string.tab_home),
        TopLevelDestination(RidesRoute, HlIcons.Rides, R.string.tab_rides),
        TopLevelDestination(CarPlayRoute, HlIcons.CarPlay, R.string.tab_carplay),
        TopLevelDestination(GarageRoute, HlIcons.Garage, R.string.tab_garage),
        TopLevelDestination(IntercomRoute, HlIcons.Intercom, R.string.tab_intercom),
    )

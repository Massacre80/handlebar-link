package com.abid.handlebarlink.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.devices.DeviceKind
import kotlinx.serialization.Serializable

/**
 * A sheet or dialog over the page under it: it covers that page instead of replacing it, so the tab bar
 * stays where the page has it.
 */
interface OverlayRoute : NavKey

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

/** Settings › Appearance › Theme, pushed over Settings on Home's back stack. */
@Serializable
data object ThemeRoute : NavKey

/** Settings › Appearance › Mode, pushed over Settings on Home's back stack. */
@Serializable
data object ModeRoute : NavKey

/** Settings › Permissions: each permission and its call to action, pushed over Settings. */
@Serializable
data object PermissionsRoute : NavKey

/**
 * Add device: the phone's paired Bluetooth devices, pushed over the current tab. [origin] is the shared-bounds
 * key of what it grows out of (Home's tile, the Devices page's button): one key per origin, so switching tabs
 * never morphs one into the other.
 */
@Serializable
data class AddDeviceRoute(
    val origin: String,
) : NavKey

/** Pair a new device: searches for devices in pairing mode and pairs inside the app; over Add device. */
@Serializable
data object PairDeviceRoute : NavKey

/**
 * "What is it?" (Paper "Flow · Add device"): a sheet over Add device for one paired device, with what the
 * list knew of it, so it survives process death without reading Bluetooth again.
 */
@Serializable
data class DeviceSheetRoute(
    val address: String,
    /** The device's own name (cleaned; its model), or its address. */
    val deviceName: String,
    /** The name the rider gave it, when it is already added. */
    val savedName: String?,
    val kind: DeviceKind,
    val audio: Boolean,
    val connected: Boolean,
) : OverlayRoute

/** "Calls": a sheet over the Intercom page choosing where call audio goes. */
@Serializable
data object CallsRoute : OverlayRoute

/** "Music": a sheet over the Intercom page choosing where media plays (Android 14+). */
@Serializable
data object MusicRoute : OverlayRoute

/** "Assistant button": a sheet over the Devices page choosing what the intercom's assistant button does. */
@Serializable
data object AssistantRoute : OverlayRoute

/** "Remove …?": a dialog over the Intercom page confirming a device's removal from the app. */
@Serializable
data class RemoveDeviceRoute(
    val address: String,
    val name: String,
) : OverlayRoute

/** Handlebar controls: what each press does; grows out of Home's Handlebar card. */
@Serializable
data object HandlebarRoute : NavKey

/** The chooser for one handlebar press ([switch]: a HandlebarSwitch name), a sheet over Handlebar controls. */
@Serializable
data class HandlebarPressRoute(
    val switch: String,
) : OverlayRoute

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
        TopLevelDestination(IntercomRoute, HlIcons.Intercom, R.string.tab_devices),
    )

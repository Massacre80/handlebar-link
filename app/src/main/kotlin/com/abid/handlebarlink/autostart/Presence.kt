package com.abid.handlebarlink.autostart

import android.os.Build
import com.abid.handlebarlink.bike.link.BikeLinkConfig

/**
 * One of this app's companion-device associations (CompanionDeviceManager): the meter linked at setup.
 * [id] is null on Android 12 (API 31–32), which lists associations by address only (`getAssociations()`).
 */
data class Association(
    val id: Int?,
    /** The device's Bluetooth address, upper case like `BluetoothDevice.getAddress()`. */
    val address: String,
    /** The name chosen at setup ("HONDA BTU 2040"); null on Android 12, which lists associations by address only. */
    val name: String? = null,
)

/** Android's companion-device presence for this app's associations (docs/PLAN.md M4). */
interface CompanionPresence {
    /** This app's associations; empty when the device has no companion-device support. A binder call. */
    fun associations(): List<Association>

    /** Asks Android to watch for [association] and tell [BikePresenceService] when it comes and goes. */
    fun startObserving(association: Association)

    /** Stops watching for [association]. */
    fun stopObserving(association: Association)
}

/** What to change so that Android watches exactly the selected bike: [observe] it, [release] the rest. */
data class PresencePlan(
    val observe: Association?,
    val release: List<Association>,
)

/**
 * Android watches only the selected bike: its association (matched by address, ignoring case) is observed,
 * every other one of this app's associations is released (a bike set up before, then replaced). No bike, or a
 * bike without an association (one chosen from "Paired with this phone", which skips Android's consent),
 * leaves nothing observed.
 */
fun planPresence(
    selected: String?,
    associations: List<Association>,
): PresencePlan {
    val observe =
        selected?.let { address ->
            associations.firstOrNull { it.address.equals(address, ignoreCase = true) }
        }
    return PresencePlan(observe, associations - setOfNotNull(observe))
}

/** Which of CompanionDeviceService's callbacks reports presence on a release (see [presenceCallbacks]). */
enum class PresenceCallbacks {
    /** `onDevicePresenceEvent(DevicePresenceEvent)`, Android 16+ (API 36). */
    EVENTS,

    /** `onDeviceAppeared/Disappeared(AssociationInfo)`, API 33–35. */
    ASSOCIATION,

    /** `onDeviceAppeared/Disappeared(String address)`, Android 12 (API 31–32). */
    ADDRESS,
}

/**
 * The one place that decides which callback family to trust. Each was deprecated by the next (API 33, API 36:
 * CompanionDeviceService reference), and on API 36+ the system also calls the deprecated AssociationInfo ones
 * after `onDevicePresenceEvent` (AOSP DevicePresenceProcessor, docs/research/02-ble-connection.md §3.2), so
 * every other family is ignored to handle each change once.
 */
fun presenceCallbacks(sdk: Int): PresenceCallbacks =
    when {
        sdk >= Build.VERSION_CODES.BAKLAVA -> PresenceCallbacks.EVENTS
        sdk >= Build.VERSION_CODES.TIRAMISU -> PresenceCallbacks.ASSOCIATION
        else -> PresenceCallbacks.ADDRESS
    }

/**
 * A `DevicePresenceEvent` (API 36) as presence: true when the meter came into BLE range or connected over
 * Bluetooth, false when it left BLE range or disconnected, null for events that say nothing about it
 * (self-managed devices, the association removed). Values from the DevicePresenceEvent reference.
 */
fun presenceOf(event: Int): Boolean? =
    when (event) {
        EVENT_BLE_APPEARED, EVENT_BT_CONNECTED -> true
        EVENT_BLE_DISAPPEARED, EVENT_BT_DISCONNECTED -> false
        else -> null
    }

/**
 * The link's policy for a bike. Presence-driven (docs/research/02-ble-connection.md §3.4, the research's
 * default) when Android watches the bike: after the link is lost and the bike is gone the link waits
 * [LINGER_MILLIS], then goes idle and the ride service ends; the next appearance starts it again. A bike
 * Android does not watch stays always armed (the ride service runs until stopped), because nothing would
 * start it again. Which mode is the default for watched bikes is settled by the on-bike tests D4/D5
 * (docs/PLAN.md M4).
 */
fun linkConfig(watched: Boolean): BikeLinkConfig = BikeLinkConfig(lingerMillis = if (watched) LINGER_MILLIS else null)

/** Fuel stops and stalls without a cold start (docs/research/02-ble-connection.md §2.5: 10 min). */
const val LINGER_MILLIS: Long = 10 * 60 * 1_000L

// DevicePresenceEvent constants (API 36, values from its reference); copied because lint's InlinedApi
// rejects the platform fields outside API 36 code, and this function is tested on the JVM.
private const val EVENT_BLE_APPEARED = 0
private const val EVENT_BLE_DISAPPEARED = 1
private const val EVENT_BT_CONNECTED = 2
private const val EVENT_BT_DISCONNECTED = 3

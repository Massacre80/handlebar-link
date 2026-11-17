package com.abid.handlebarlink.autostart

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * Android binds this service and reports here when the bike Android watches for ([PresenceArming]) comes
 * and goes (CompanionDeviceService; companion-device-pairing "Keep companion apps awake"). Only the
 * callback family of this release is trusted ([presenceCallbacks]); [PresenceEvents] does the rest.
 * Exported because the system binds it; BIND_COMPANION_DEVICE_SERVICE, held only by the system, guards it.
 */
@AndroidEntryPoint
class BikePresenceService : CompanionDeviceService() {
    @Inject lateinit var events: PresenceEvents

    @Inject lateinit var logger: Logger

    private val log by lazy { logger.child("autostart") }
    private val callbacks = presenceCallbacks(Build.VERSION.SDK_INT)

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val present = presenceOf(event.event)
        log.info("presence.event", "association" to event.associationId, "event" to event.event)
        if (callbacks == PresenceCallbacks.EVENTS && present != null) events.onAssociation(event.associationId, present)
    }

    // The system calls the AssociationInfo overloads from API 33, where they were added.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Deprecated("Android 16 reports onDevicePresenceEvent; used on API 33–35")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (callbacks == PresenceCallbacks.ASSOCIATION) report(associationInfo, present = true)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Deprecated("Android 16 reports onDevicePresenceEvent; used on API 33–35")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (callbacks == PresenceCallbacks.ASSOCIATION) report(associationInfo, present = false)
    }

    @Deprecated("API 33 reports the AssociationInfo overload; used on Android 12")
    override fun onDeviceAppeared(address: String) {
        if (callbacks == PresenceCallbacks.ADDRESS) report(address, present = true)
    }

    @Deprecated("API 33 reports the AssociationInfo overload; used on Android 12")
    override fun onDeviceDisappeared(address: String) {
        if (callbacks == PresenceCallbacks.ADDRESS) report(address, present = false)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun report(
        info: AssociationInfo,
        present: Boolean,
    ) {
        val address = info.deviceMacAddress?.toString()
        if (address == null) {
            log.warn("presence.no_address", "association" to info.id, "present" to present)
        } else {
            report(address, present)
        }
    }

    private fun report(
        address: String,
        present: Boolean,
    ) {
        // MacAddress prints lower case; BluetoothDevice addresses (and the stored bike) are upper case.
        val normalised = address.uppercase(Locale.ROOT)
        log.info("presence.device", "address" to normalised, "present" to present)
        events.onAddress(normalised, present)
    }
}

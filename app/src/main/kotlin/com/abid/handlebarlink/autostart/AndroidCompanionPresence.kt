package com.abid.handlebarlink.autostart

import android.companion.CompanionDeviceManager
import android.companion.DeviceNotAssociatedException
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

/**
 * [CompanionPresence] with CompanionDeviceManager. The one version branch of presence observing (CDM
 * reference): Android 16+ (API 36) observes by association id with `ObservingDevicePresenceRequest`, which
 * deprecates the address overloads; API 31–35 only have `startObservingDevicePresence(String address)`.
 * Listing: `getMyAssociations()` from API 33, `getAssociations()` (addresses, no ids) on Android 12.
 * Every call first checks FEATURE_COMPANION_DEVICE_SETUP, as the reference requires.
 */
class AndroidCompanionPresence
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : CompanionPresence {
        private val log = logger.child("autostart")

        private val manager: CompanionDeviceManager?
            get() =
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
                    context.getSystemService(CompanionDeviceManager::class.java)
                } else {
                    null
                }

        override fun associations(): List<Association> {
            val manager = manager ?: return emptyList()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.mapNotNull { info ->
                    info.deviceMacAddress?.let { Association(info.id, it.toString().uppercase(Locale.ROOT)) }
                }
            } else {
                // Android 12: getMyAssociations() does not exist yet.
                @Suppress("DEPRECATION")
                manager.associations.map { Association(id = null, address = it.uppercase(Locale.ROOT)) }
            }
        }

        override fun startObserving(association: Association) {
            val manager = manager ?: return
            val id = association.id
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null) {
                    manager.startObservingDevicePresence(request(id))
                } else {
                    // API 31–35: the request overload does not exist yet.
                    @Suppress("DEPRECATION")
                    manager.startObservingDevicePresence(association.address)
                }
                log.info("presence.observe", "association" to id, "address" to association.address)
            } catch (e: DeviceNotAssociatedException) {
                log.warn("presence.observe_failed", "address" to association.address, "error" to e.message)
            } catch (e: IllegalArgumentException) {
                // The reference lists no exception for the request overload; an id the system no longer
                // knows (association removed in Settings meanwhile) may be refused: log it, never crash.
                log.warn("presence.observe_failed", "association" to id, "error" to e.message)
            }
        }

        override fun stopObserving(association: Association) {
            val manager = manager ?: return
            val id = association.id
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && id != null) {
                    manager.stopObservingDevicePresence(request(id))
                } else {
                    // API 31–35: the request overload does not exist yet.
                    @Suppress("DEPRECATION")
                    manager.stopObservingDevicePresence(association.address)
                }
                log.info("presence.release", "association" to id, "address" to association.address)
            } catch (e: DeviceNotAssociatedException) {
                log.warn("presence.release_failed", "address" to association.address, "error" to e.message)
            } catch (e: IllegalArgumentException) {
                log.warn("presence.release_failed", "association" to id, "error" to e.message)
            }
        }

        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        private fun request(id: Int): ObservingDevicePresenceRequest =
            ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()
    }

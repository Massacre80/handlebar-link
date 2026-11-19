package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadset
import com.abid.handlebarlink.carplay.engine.android.CarPlayHeadsetSource
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.devices.DeviceSettings
import com.abid.handlebarlink.devices.PHONE
import com.abid.handlebarlink.devices.SavedDevice
import com.abid.handlebarlink.devices.primary
import com.abid.handlebarlink.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the rider sends calls (the Intercom page), as our CarPlay's calls and Siri see it: null when the rider chose
 * [PHONE]; else the chosen device, or the primary one (the first intercom unless the rider picked another), by its
 * address and its own Bluetooth name; with no device added, any connected headset. The same choice the held Telecom
 * call follows for CarLink's calls (callaudio/IntercomCallRoute).
 */
internal fun carPlayHeadset(
    devices: List<SavedDevice>,
    primary: String?,
    calls: String?,
): CarPlayHeadset? {
    if (calls == PHONE) return null
    val target = devices.firstOrNull { it.address == calls } ?: devices.primary(primary)
    return CarPlayHeadset(target?.address, target?.model)
}

/**
 * [CarPlayHeadsetSource] from the rider's devices: kept current in the app's scope, so the audio thread that asks
 * ([preferred]) only reads a value. Until the settings are read, any connected headset. Building it only starts a
 * coroutine (docs/LESSONS.md 60).
 */
@Singleton
class RiderHeadsets
    @Inject
    constructor(
        devices: DeviceSettings,
        @ApplicationScope scope: CoroutineScope,
        logger: Logger,
    ) : CarPlayHeadsetSource {
        private val log = logger.child("carplay")

        private val current: StateFlow<CarPlayHeadset?> =
            combine(devices.devices, devices.primary, devices.calls, ::carPlayHeadset)
                .catch { error ->
                    // A settings file that cannot be read keeps the last choice (any headset at first).
                    if (error !is IOException) throw error
                    log.warn("carplay.headset.settings_failed", "error" to error.toString())
                }.stateIn(scope, SharingStarted.Eagerly, CarPlayHeadset())

        override fun preferred(): CarPlayHeadset? = current.value
    }

package com.abid.handlebarlink.devices

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last battery each headset reported. A headset reports (Apple's +IPHONEACCEV, a vendor-specific
 * headset event) once right after it connects and then only on each 10 % change; Android has no public way
 * to ask for it (getBatteryLevel() and BATTERY_LEVEL_CHANGED are system APIs; Apple's spec has no phone-side
 * query). So, as the research of 2026-11-18 recommends:
 * - the listener runs for the whole process, started in Application.onCreate; [HeadsetConnectionReceiver]
 *   (a manifest receiver, allowed for headset connection changes) starts the process when a headset
 *   connects, so the report that follows is heard even when the app was not running;
 * - each level is stored with its time and restored at start (shown only while that headset is connected);
 *   it is forgotten on disconnect, and one older than [KEEP_MILLIS] is not trusted.
 */
@Singleton
class HeadsetBatteries
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
        private val store: DataStore<Preferences>,
        private val time: TimeProvider,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val log = logger.child("battery")
        private val reported = MutableStateFlow<Map<String, Int>>(emptyMap())
        private var started = false

        /** Percent per headset address. */
        val levels: StateFlow<Map<String, Int>> = reported.asStateFlow()

        /** Listens from now on, for the life of the process (once). */
        fun start() {
            if (started) return
            started = true
            // Exported: the Bluetooth app sends these ("Broadcasts overview"). It only delivers them to apps
            // holding BLUETOOTH_CONNECT; without it nothing arrives, which is the same as no report.
            ContextCompat.registerReceiver(context, receiver, FILTER, ContextCompat.RECEIVER_EXPORTED)
            scope.launch {
                val now = time.clock.millis()
                val stored = stored(store.data.first(), now)
                log.info("battery.restored", "count" to stored.size)
                // A report heard meanwhile is newer than anything stored.
                reported.update { stored + it }
            }
        }

        /** The stored levels young enough to trust at [now]. */
        private fun stored(
            preferences: Preferences,
            now: Long,
        ): Map<String, Int> =
            preferences
                .asMap()
                .keys
                .filter { it.name.startsWith(LEVEL_PREFIX) }
                .mapNotNull { key ->
                    val address = key.name.removePrefix(LEVEL_PREFIX)
                    val level = preferences[levelKey(address)]
                    val at = preferences[atKey(address)]
                    if (level != null && at != null && now - at in 0..KEEP_MILLIS) address to level else null
                }.toMap()

        private fun remember(
            address: String,
            percent: Int,
        ) {
            reported.update { it + (address to percent) }
            scope.launch {
                store.edit {
                    it[levelKey(address)] = percent
                    it[atKey(address)] = time.clock.millis()
                }
            }
        }

        /** Forgets [address]'s level: it disconnected. Returns once the store is written. */
        suspend fun forget(address: String) {
            reported.update { it - address }
            store.edit {
                it.remove(levelKey(address))
                it.remove(atKey(address))
            }
        }

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val address =
                        IntentCompat
                            .getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            ?.address ?: return
                    if (intent.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                        val state =
                            intent.getIntExtra(
                                BluetoothProfile.EXTRA_STATE,
                                BluetoothProfile.STATE_DISCONNECTED,
                            )
                        if (state == BluetoothProfile.STATE_DISCONNECTED) scope.launch { forget(address) }
                        return
                    }
                    batteryPercent(intent)?.let { percent ->
                        log.info("battery.report", "percent" to percent)
                        remember(address, percent)
                    }
                }
            }

        private companion object {
            /** How long a stored level is trusted: an intercom loses about 10 % an hour in use. */
            const val KEEP_MILLIS = 6 * 60 * 60 * 1000L

            /** Level keys; the time keys use another prefix, so they never match this one. */
            const val LEVEL_PREFIX = "headset_battery_level_"

            fun levelKey(address: String) = intPreferencesKey("$LEVEL_PREFIX$address")

            fun atKey(address: String) = longPreferencesKey("headset_battery_at_$address")

            /** Apple's Bluetooth company id: +XAPL and +IPHONEACCEV arrive under it. */
            const val APPLE = 76
            val FILTER =
                IntentFilter().apply {
                    addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
                    addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$APPLE")
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                }
        }
    }

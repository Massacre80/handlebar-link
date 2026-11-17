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
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last battery each headset reported, for the whole app process. A headset reports (Apple's
 * +IPHONEACCEV, a vendor-specific headset event) once right after it connects and then only on each 10 %
 * change, so a listener that lives only while Home is on screen misses it (2026-11-18: the V6 Pro+ sent
 * 50 % while the rider was on the "What is it?" sheet). Started with the app; a level is forgotten when its
 * headset disconnects.
 */
@Singleton
class HeadsetBatteries
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
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
                        if (state == BluetoothProfile.STATE_DISCONNECTED) reported.update { it - address }
                        return
                    }
                    batteryPercent(intent)?.let { percent ->
                        log.info("battery.report", "percent" to percent)
                        reported.update { it + (address to percent) }
                    }
                }
            }

        private companion object {
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

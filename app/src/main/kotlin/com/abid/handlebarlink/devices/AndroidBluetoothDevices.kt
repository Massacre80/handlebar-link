package com.abid.handlebarlink.devices

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.setup.cleanName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * [BluetoothDevices] from Android's public Bluetooth APIs (developer.android.com, "Find Bluetooth devices",
 * "Bluetooth profiles", "Bluetooth permissions"): the bonded set for the list; the Headset and A2DP profile
 * proxies and their connection broadcasts for live state; the battery each headset last reported, from
 * [HeadsetBatteries] (Apple's +IPHONEACCEV, the only public route to a headset's battery).
 * Everything needs BLUETOOTH_CONNECT ("Nearby devices"), checked before each call; a SecurityException (it was
 * revoked meanwhile) reads as "not allowed".
 */
class AndroidBluetoothDevices
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
        private val batteries: HeadsetBatteries,
    ) : BluetoothDevices {
        private val log = logger.child("devices")
        private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

        override fun isOn(): Boolean = adapter?.isEnabled == true

        override fun paired(): List<PairedDevice>? {
            if (!allowed()) return null
            return try {
                // Read here, in the function that checked the permission (lint MissingPermission, LESSONS 28).
                adapter?.bondedDevices.orEmpty().map { device ->
                    val type = device.bluetoothClass
                    val audio = type?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
                    PairedDevice(device.address, cleanName(device.name), audio, suggestedKind(type?.deviceClass, audio))
                }
            } catch (e: SecurityException) {
                log.warn("devices.paired.denied", "error" to e.message)
                null
            }
        }

        override fun statuses(): Flow<Map<String, DeviceStatus>> =
            combine(connections(), batteries.levels) { profiles, levels ->
                val calls = profiles[BluetoothProfile.HEADSET].orEmpty()
                val music = profiles[BluetoothProfile.A2DP].orEmpty()
                (calls + music).associateWith {
                    DeviceStatus(connected = true, battery = levels[it], calls = it in calls, music = it in music)
                }
            }.distinctUntilChanged()

        /** The addresses connected now per profile (Headset: calls, A2DP: music), while collected. */
        private fun connections(): Flow<Map<Int, Set<String>>> =
            callbackFlow {
                val adapter = adapter
                if (adapter == null || !allowed()) {
                    trySend(emptyMap())
                    awaitClose {}
                    return@callbackFlow
                }
                // Addresses connected per profile.
                val connected =
                    mutableMapOf(
                        BluetoothProfile.HEADSET to setOf<String>(),
                        BluetoothProfile.A2DP to setOf(),
                    )
                val proxies = mutableMapOf<Int, BluetoothProfile>()

                fun publish() = trySend(connected.toMap())
                val listener =
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(
                            profile: Int,
                            proxy: BluetoothProfile,
                        ) {
                            proxies[profile] = proxy
                            connected[profile] = connectedTo(proxy)
                            publish()
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            proxies.remove(profile)
                            connected[profile] = emptySet()
                            publish()
                        }
                    }
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context,
                            intent: Intent,
                        ) {
                            val device =
                                IntentCompat.getParcelableExtra(
                                    intent,
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java,
                                )
                                    ?: return
                            when (intent.action) {
                                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                                    connected[BluetoothProfile.HEADSET] =
                                        change(connected, BluetoothProfile.HEADSET, device, intent)
                                }

                                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                                    connected[BluetoothProfile.A2DP] =
                                        change(connected, BluetoothProfile.A2DP, device, intent)
                                }
                            }
                            publish()
                        }
                    }
                // Exported: Bluetooth broadcasts come from the Bluetooth app, a privileged app, not the system
                // ("Broadcasts overview", context-registered receivers).
                ContextCompat.registerReceiver(context, receiver, CONNECTIONS, ContextCompat.RECEIVER_EXPORTED)
                adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
                adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
                publish()
                awaitClose {
                    context.unregisterReceiver(receiver)
                    proxies.forEach { (profile, proxy) -> adapter.closeProfileProxy(profile, proxy) }
                }
            }.distinctUntilChanged()

        private fun connectedTo(proxy: BluetoothProfile): Set<String> {
            if (!allowed()) return emptySet()
            return try {
                proxy.connectedDevices.map { it.address }.toSet()
            } catch (e: SecurityException) {
                log.warn("devices.connected.denied", "error" to e.message)
                emptySet()
            }
        }

        private fun change(
            connected: Map<Int, Set<String>>,
            profile: Int,
            device: BluetoothDevice,
            intent: Intent,
        ): Set<String> {
            val now = connected[profile].orEmpty()
            return when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)) {
                BluetoothProfile.STATE_CONNECTED -> now + device.address
                else -> now - device.address
            }
        }

        private fun allowed() =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        private companion object {
            val CONNECTIONS =
                IntentFilter().apply {
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                }
        }
    }

/**
 * The battery in percent from an Apple accessory report, `+IPHONEACCEV=<count>,<key>,<value>,…`: key 1 is the
 * battery level 0–9, read as (level + 1) × 10 %. Null for any other report or a malformed one. The command
 * name has no public constant, so it is matched as text; arguments may arrive as numbers or strings.
 */
internal fun batteryPercent(intent: Intent): Int? {
    @Suppress("DEPRECATION") // Object[] extras have no typed accessor.
    val args = intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*>
    val accessory = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) == IPHONEACCEV
    return if (accessory && args != null) batteryPercent(args.map { it?.toString()?.trim()?.toIntOrNull() }) else null
}

/** [batteryPercent] on the parsed arguments: a count, then key/value pairs. */
internal fun batteryPercent(args: List<Int?>): Int? =
    (0 until (args.firstOrNull() ?: 0)).firstNotNullOfOrNull { pair ->
        val key = args.getOrNull(1 + pair * 2)
        args
            .getOrNull(2 + pair * 2)
            ?.takeIf { key == BATTERY_KEY && it in 0..BATTERY_MAX_LEVEL }
            ?.let { (it + 1) * BATTERY_STEP }
    }

private const val IPHONEACCEV = "+IPHONEACCEV"
private const val BATTERY_KEY = 1
private const val BATTERY_MAX_LEVEL = 9
private const val BATTERY_STEP = 10

/** What a device looks like by its class: hands-free and wearable headsets are what intercoms announce. */
internal fun suggestedKind(
    deviceClass: Int?,
    audio: Boolean,
): DeviceKind =
    when (deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        -> DeviceKind.INTERCOM

        else -> if (audio) DeviceKind.HEADSET else DeviceKind.OTHER
    }

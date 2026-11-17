package com.abid.handlebarlink.devices

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * [HeadsetLink] with public APIs only: a companion-device association for the paired headset (a classic
 * [BluetoothDeviceFilter] on its address, single device: the consent lists bonded devices too, so the
 * headset need not be in range), then Android 17's `BluetoothDevice.connect()`, which the Bluetooth service
 * allows for the caller's associated device (`enforceCdmAssociationIfNotBluetoothPrivileged`) and which
 * connects every profile the rider allows. Where the platform switch for it is off, Android answers with a
 * SecurityException: [Reconnect.NOT_SUPPORTED].
 */
class AndroidHeadsetLink
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : HeadsetLink {
        private val log = logger.child("reconnect")
        private val manager: CompanionDeviceManager? get() =
            context.getSystemService(
                CompanionDeviceManager::class.java,
            )

        override fun isLinked(address: String): Boolean {
            val manager = manager ?: return false
            val linked =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
                } else {
                    @Suppress("DEPRECATION") // Android 12: addresses only.
                    manager.associations
                }
            return linked.any { it.equals(address, ignoreCase = true) }
        }

        override suspend fun request(address: String): IntentSender? =
            suspendCancellableCoroutine { continuation ->
                val manager = manager
                if (manager == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                val callback =
                    object : CompanionDeviceManager.Callback() {
                        override fun onAssociationPending(intentSender: IntentSender) {
                            if (continuation.isActive) continuation.resume(intentSender)
                        }

                        @Deprecated("Android 12 (API 31–32) reports the consent screen here")
                        override fun onDeviceFound(intentSender: IntentSender) {
                            if (continuation.isActive) continuation.resume(intentSender)
                        }

                        override fun onFailure(error: CharSequence?) {
                            log.warn("reconnect.link.failed", "error" to error)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                val request =
                    AssociationRequest
                        .Builder()
                        .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
                        .setSingleDevice(true)
                        .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.associate(request, context.mainExecutor, callback)
                } else {
                    @Suppress("DEPRECATION") // Android 12: the Executor overload does not exist yet.
                    manager.associate(request, callback, Handler(Looper.getMainLooper()))
                }
                log.info("reconnect.link.request")
            }

        override fun linkedFrom(
            resultCode: Int,
            data: Intent?,
        ): String? {
            if (resultCode != Activity.RESULT_OK || data == null) return null
            val address =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data
                        .getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                        ?.deviceMacAddress
                        ?.toString()
                } else {
                    @Suppress("DEPRECATION") // Android 12: a classic filter returns the BluetoothDevice.
                    data
                        .getParcelableExtra<android.bluetooth.BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
                        ?.address
                }
            // MacAddress prints lower case; BluetoothDevice addresses are upper case.
            return address?.uppercase(Locale.ROOT)
        }

        override fun reconnect(address: String): Reconnect {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            val result =
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN -> {
                        Reconnect.NOT_SUPPORTED
                    }

                    adapter == null || !adapter.isEnabled -> {
                        Reconnect.FAILED
                    }

                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                        PackageManager.PERMISSION_GRANTED -> {
                        Reconnect.FAILED
                    }

                    !isLinked(address) -> {
                        Reconnect.NOT_LINKED
                    }

                    else -> {
                        try {
                            val status = adapter.getRemoteDevice(address).connect()
                            if (status == BluetoothStatusCodes.SUCCESS) Reconnect.STARTED else Reconnect.FAILED
                        } catch (e: SecurityException) {
                            // Linked, yet refused: this build keeps connect() for privileged apps.
                            log.warn("reconnect.denied", "error" to e.message)
                            Reconnect.NOT_SUPPORTED
                        } catch (e: IllegalArgumentException) {
                            log.warn("reconnect.address", "error" to e.message)
                            Reconnect.FAILED
                        }
                    }
                }
            log.info("reconnect.result", "result" to result.name)
            return result
        }
    }

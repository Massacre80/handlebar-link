package com.abid.handlebarlink.setup

import android.app.Activity
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Links the tapped meter through Android's companion-device consent (CompanionDeviceManager; docs/PLAN.md
 * "First-time setup"): only that meter (its exact name and the Vehicle Information service,
 * `setSingleDevice(true)`). One branch: API 33+ uses the Executor callback and `EXTRA_ASSOCIATION`;
 * Android 12 (API 31–32) the Handler callback and `EXTRA_DEVICE`, a BLE `ScanResult`.
 */
class AndroidMeterAssociation
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : MeterAssociation {
        private val log = logger.child("setup.link")

        override suspend fun request(meter: FoundMeter): IntentSender =
            suspendCancellableCoroutine { continuation ->
                val manager =
                    context.getSystemService(CompanionDeviceManager::class.java)
                        ?: throw SetupException(SetupProblem.NOT_LINKED)
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
                            log.warn("link.failed", "error" to error)
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    SetupException(SetupProblem.NOT_LINKED),
                                )
                            }
                        }
                    }
                val request = associationRequest(meter)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.associate(request, context.mainExecutor, callback)
                } else {
                    // Android 12: the Executor overload does not exist yet.
                    @Suppress("DEPRECATION")
                    manager.associate(request, callback, Handler(Looper.getMainLooper()))
                }
                log.info("link.request", "meter" to meter.name)
            }

        override fun addressFrom(
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
                    // Android 12: a BLE device filter returns the chosen device as a ScanResult.
                    @Suppress("DEPRECATION")
                    (data.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE))?.device?.address
                }
            // MacAddress prints lower case; BluetoothDevice addresses are upper case.
            return address?.uppercase(Locale.ROOT)
        }

        private fun associationRequest(meter: FoundMeter): AssociationRequest {
            // The tapped device's cleaned name, then anything: the meter's advertised name ends in bytes
            // that are not text, so the exact name never matches (SAB_PROTOCOL.md §1.1, 2026-11-17). No
            // service filter: a fallback device chosen from the list does not advertise it.
            val filter =
                BluetoothLeDeviceFilter
                    .Builder()
                    .setNamePattern(Pattern.compile(Pattern.quote(meter.name) + ".*", Pattern.DOTALL))
                    .build()
            return AssociationRequest
                .Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(true)
                .build()
        }
    }

package com.abid.handlebarlink.carplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The runtime permissions our CarPlay receiver needs on Android [sdk] (docs/PLAN.md "CarPlay receiver",
 * permissions table decided 2026-11-19): Bluetooth for the RFCOMM dial to the iPhone; Nearby Wi-Fi for the
 * Wi-Fi Direct group from Android 13 (Android 12/12L need precise location instead, wifi-direct guide, asked together
 * with approximate: "If you try to request only ACCESS_FINE_LOCATION, the system ignores the request on some releases
 * of Android 12", location permissions guide; the rider must pick precise, since Wi-Fi Direct checks it); local
 * network from Android 17, where apps targeting SDK 37 (ours) are blocked from it without the grant
 * (local-network-permission guide); the microphone for Siri and calls (C5). Bluetooth, Nearby Wi-Fi and local network
 * are in the Nearby devices group, so a rider who allowed Bluetooth is not asked again for them ("Pre-granted
 * scenario"); the microphone is its own dialog. Lint's InlinedApi cannot see a guard on the [sdk] parameter,
 * which keeps the list testable for every release; the guards are the `if`s below.
 */
@SuppressLint("InlinedApi") // String constants copied in at compile time, each added only when [sdk] has it.
internal fun carPlayPermissions(sdk: Int): List<String> =
    buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (sdk >= ANDROID_17) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        add(Manifest.permission.RECORD_AUDIO)
    }

/** The [carPlayPermissions] of the running Android release. */
fun carPlayPermissions(): List<String> = carPlayPermissions(Build.VERSION.SDK_INT)

/** Which of CarPlay's runtime permissions are missing now; read fresh each call, never cached (permissions skill). */
fun interface CarPlayPermissions {
    /** The missing permissions; empty when CarPlay may run. */
    fun missing(): List<String>
}

/** [CarPlayPermissions] from Android's permission checks. */
class AndroidCarPlayPermissions
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CarPlayPermissions {
        override fun missing(): List<String> =
            carPlayPermissions().filterNot {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
    }

/** Android 17 (API 37). */
private const val ANDROID_17 = 37

package com.abid.handlebarlink.ui.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.abid.handlebarlink.carplay.carPlayPermissions

/**
 * Our own CarPlay receiver's runtime permissions (carplay/carPlayPermissions: Bluetooth, Nearby Wi-Fi or, on Android
 * 12, precise location, local network from Android 17, and the microphone for Siri and calls), asked together. All but
 * the microphone are in the Nearby devices group, so a rider who allowed Bluetooth sees only the microphone's dialog.
 * CarPlay does not run without any of them (owner, 2026-11-19). [onResult] reports each answer.
 */
@Composable
fun rememberCarPlayAccess(onResult: (Boolean) -> Unit = {}): AccessState =
    rememberRuntimeAccess(remember { carPlayPermissions() }, onResult)

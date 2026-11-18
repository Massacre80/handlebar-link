package com.abid.handlebarlink.ui.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.abid.handlebarlink.carplay.carPlayPermissions

/**
 * Our own CarPlay receiver's runtime permissions (carplay/carPlayPermissions: Bluetooth, Nearby Wi-Fi or, on Android
 * 12, precise location, and local network from Android 17), asked together; in the Nearby devices group, so a rider
 * who allowed Bluetooth sees no dialog for the rest. [onResult] reports each answer.
 */
@Composable
fun rememberCarPlayAccess(onResult: (Boolean) -> Unit = {}): AccessState =
    rememberRuntimeAccess(remember { carPlayPermissions() }, onResult)

package com.abid.handlebarlink.ride

import android.content.pm.ServiceInfo
import com.abid.handlebarlink.carplay.engine.CarPlayState

/**
 * The ride service's foreground types: always `connectedDevice` (the bike and CarPlay's radios), `microphone` while
 * CarPlay may record ([microphone], [keepsMicrophone]). "If you need to add more service types, you can call
 * startForeground() again" with all of them (developer.android.com, "Launch a foreground service"). Our CarPlay's
 * calls route themselves (`setCommunicationDevice`, :carplay:engine-android CallAudio), so no `phoneCall` type.
 */
internal fun rideServiceTypes(microphone: Boolean): Int {
    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    if (microphone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    return types
}

/**
 * Whether the ride service should run with the `microphone` type, so CarPlay's Siri and calls hear the rider with the
 * screen off or the app in the background (docs/PLAN.md "C5 audio", "Microphone in the background").
 *
 * `RECORD_AUDIO` is a while-in-use permission: a `microphone` service may only be started, or the type added, while
 * the app is visible; from the background Android 14+ throws a SecurityException, and Android 11-13 starts it
 * without microphone access (developer.android.com, "Restrictions on starting foreground services that need
 * while-in-use permissions"; none of the listed exemptions covers a companion-device app). So:
 * - it is added only while our CarPlay is [CarPlayState.Connected], [permitted] (RECORD_AUDIO granted, the type's
 *   runtime prerequisite, "Foreground service types": Microphone) and the app [visible] (the CarPlay display);
 * - once held ([has]) it is kept across screen-off, the app leaving the screen, and CarPlay's drops and retries
 *   (`Connecting`, `Recovering`): giving it up mid-ride could not be undone until the rider opened the app again;
 * - it is given back when CarPlay no longer runs (`Stopped`, `Idle`: Disconnect, the ride over) or the
 *   permission was withdrawn.
 */
internal fun keepsMicrophone(
    has: Boolean,
    carPlay: CarPlayState,
    visible: Boolean,
    permitted: Boolean,
): Boolean =
    when {
        !permitted -> false
        carPlay is CarPlayState.Stopped || carPlay is CarPlayState.Idle -> false
        has -> true
        else -> carPlay is CarPlayState.Connected && visible
    }

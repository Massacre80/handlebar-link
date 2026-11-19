package com.abid.handlebarlink.ride

import android.content.pm.ServiceInfo
import com.abid.handlebarlink.carplay.engine.CarPlayState

/**
 * The ride service's foreground types: always `connectedDevice` (the bike and CarPlay's radios), `phoneCall` while a
 * Telecom call is held for CarLink's call ([holdingCall], callaudio/), `microphone` while CarPlay may record
 * ([microphone], [keepsMicrophone]). "If you need to add more service types, you can call startForeground() again"
 * with all of them (developer.android.com, "Launch a foreground service").
 */
internal fun rideServiceTypes(
    holdingCall: Boolean,
    microphone: Boolean,
): Int {
    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    if (holdingCall) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
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
 * - it is given back when CarPlay no longer runs (`Stopped`, `Idle`: the switch off, Disconnect, the ride over) or the
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

/**
 * Whether the ride service holds a Telecom call for a voice call it sees on the phone (callaudio/IntercomCallRoute).
 * While our own CarPlay is connected the call is ours: our receiver puts its sound and microphone on the intercom
 * itself (`setCommunicationDevice`, :carplay:engine-android CallAudio) and a held Telecom call would take over the
 * same route (Telecom becomes the audio mode's owner) and fight it. CarLink's calls, with our CarPlay off, keep the
 * held call.
 */
internal fun holdsTelecomCall(ourCarPlayConnected: Boolean): Boolean = !ourCarPlayConnected

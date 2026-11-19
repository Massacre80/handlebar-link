package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayKey
import com.abid.handlebarlink.carplay.protocol.plist.Plist

/** The event-channel commands for a key press and for Siri, as DiPlay sends them. */
internal object KeyCommands {
    /**
     * One press of [key]: its one-byte report (the index into the device's usage array, AirPlayHid.mediaReport and
     * telephonyReport, AirPlayHid.kt:70-72), then the release (index 0), as DiPlay's `sendMedia` and `sendTelephony`
     * (AirPlaySession.kt:380-388).
     */
    fun press(key: CarPlayKey): List<Plist.Dict> {
        val (uid, index) = KEYS.getValue(key)
        return listOf(
            HidDevices.hidCommand(uid, byteArrayOf(index.toByte())),
            HidDevices.hidCommand(uid, byteArrayOf(RELEASE.toByte())),
        )
    }

    /**
     * Siri as a car's voice button opens it: `requestSiri` with siriAction 2, then 3 (DiPlay AirPlaySession.invokeSiri,
     * AirPlaySession.kt:390-393), keys in DiPlay's order.
     */
    fun siri(): List<Plist.Dict> =
        listOf(SIRI_START, SIRI_END).map {
            Plist.dict("type" to "requestSiri", "params" to Plist.dict("siriAction" to it))
        }

    /** The release report: index 0, both descriptors' first usage (0x00, none). */
    private const val RELEASE = 0

    // Media usage array (AirPlayHid.kt:117-123; CarPlayMediaButton.kt:13-17): none, play, pause, play/pause, next,
    // previous, AC search.
    private const val MEDIA_PLAY = 1
    private const val MEDIA_PAUSE = 2
    private const val MEDIA_PLAY_PAUSE = 3
    private const val MEDIA_NEXT = 4
    private const val MEDIA_PREVIOUS = 5

    // Telephony usage array (AirPlayHid.kt:125-128): none, hook switch, flash, drop, phone mute, …; DiPlay sends only
    // these two (CarPlayController.kt:3723-3724).
    private const val TELEPHONY_HOOK_SWITCH = 1
    private const val TELEPHONY_DROP = 3

    private const val SIRI_START = 2L
    private const val SIRI_END = 3L

    /** Each key's device and index in that device's usage array. */
    private val KEYS: Map<CarPlayKey, Pair<Int, Int>> =
        mapOf(
            CarPlayKey.PLAY to (HidDevices.MEDIA_UID to MEDIA_PLAY),
            CarPlayKey.PAUSE to (HidDevices.MEDIA_UID to MEDIA_PAUSE),
            CarPlayKey.PLAY_PAUSE to (HidDevices.MEDIA_UID to MEDIA_PLAY_PAUSE),
            CarPlayKey.NEXT to (HidDevices.MEDIA_UID to MEDIA_NEXT),
            CarPlayKey.PREVIOUS to (HidDevices.MEDIA_UID to MEDIA_PREVIOUS),
            CarPlayKey.HOOK_SWITCH to (HidDevices.TELEPHONY_UID to TELEPHONY_HOOK_SWITCH),
            CarPlayKey.DROP to (HidDevices.TELEPHONY_UID to TELEPHONY_DROP),
        )
}

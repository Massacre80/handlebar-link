package com.abid.handlebarlink.carplay.engine

import com.abid.handlebarlink.carplay.protocol.iap2.CallStateUpdate

/**
 * A key the receiver presses on the iPhone through the HID devices `/info` declares (DiPlay AirPlayHid.kt:117-133):
 * the media keys and the telephony keys DiPlay sends. Each is an index into its device's report array.
 *
 * Left out because DiPlay never sends them: the telephony descriptor's Phone Mute (index 4), flash, redial and keypad,
 * and the media descriptor's AC Search (index 6). The media descriptor has no volume usage, so volume stays with the
 * phone's own audio, as in DiPlay.
 */
public enum class CarPlayKey {
    /** Media index 1, Play (0xB0) (CarPlayMediaButton.PLAY). */
    PLAY,

    /** Media index 2, Pause (0xB1) (CarPlayMediaButton.PAUSE). */
    PAUSE,

    /** Media index 3, Play/Pause (0xCD) (CarPlayMediaButton.PLAY_PAUSE). */
    PLAY_PAUSE,

    /** Media index 4, Scan Next Track (0xB5) (CarPlayMediaButton.NEXT). */
    NEXT,

    /** Media index 5, Scan Previous Track (0xB6) (CarPlayMediaButton.PREVIOUS). */
    PREVIOUS,

    /** Telephony index 1, Hook Switch (0x20): answers a ringing call (CarPlayController.answerCall, :750, :3723). */
    HOOK_SWITCH,

    /** Telephony index 3, Drop (0x26): ends the call or declines a ringing one (CarPlayController.endCall, :753). */
    DROP,
}

/** One of the iPhone's calls ([CarPlayLink.calls]). */
public data class CarPlayCall(
    /** What the call is doing now. */
    val status: CarPlayCallStatus,
)

/**
 * The live statuses of an iAP2 CallStateUpdate (DiPlay `BydCarPlayCall.kt:22-24, 82-88`); [iap2] is the wire value.
 * Disconnected (0) and disconnecting (6) end the call, so it leaves [CarPlayLink.calls] instead.
 */
public enum class CarPlayCallStatus(
    /** The status as the iPhone sends it. */
    public val iap2: Int,
) {
    /** An outgoing call is being placed. */
    SENDING(CallStateUpdate.SENDING),

    /** An incoming call rings. */
    RINGING(CallStateUpdate.RINGING),

    /** The call is connecting. */
    CONNECTING(CallStateUpdate.CONNECTING),

    /** The call is on. */
    ACTIVE(CallStateUpdate.ACTIVE),

    /** The call is on hold. */
    HELD(CallStateUpdate.HELD),
}

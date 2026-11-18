package com.abid.handlebarlink.controls

import com.abid.handlebarlink.bike.protocol.HandlebarSwitch

/** What a button can do: a handlebar press or the intercom's assistant button. Siri comes with our CarPlay. */
enum class RiderAction {
    /** Play or pause CarPlay. */
    PLAY_PAUSE,

    /** CarPlay's next track. */
    NEXT,

    /** CarPlay's previous track. */
    PREVIOUS,

    /** Louder: the phone's volume for what is playing (music, or the call), which the helmet hears. */
    VOLUME_UP,

    /** Quieter, as [VOLUME_UP]. */
    VOLUME_DOWN,

    /** Mutes or unmutes what is playing. */
    MUTE,

    /** The screen a step brighter (needs "Modify system settings"). */
    BRIGHTNESS_UP,

    /** The screen a step dimmer, as [BRIGHTNESS_UP]. */
    BRIGHTNESS_DOWN,

    /** CarPlay when this app is in front, else this app. */
    SWITCH_SCREEN,

    /** CarPlay (CarLink) in front. */
    OPEN_CARPLAY,

    /** This app in front. */
    OPEN_APP,

    /** Speaks the primary device's battery in the helmet. */
    BATTERY,

    /** Speaks the time in the helmet. */
    TIME,

    /** Nothing. */
    NOTHING,
}

/** A handlebar press, handed to whatever it is mapped to. */
fun interface ButtonPresses {
    /** [switch] was pressed. */
    fun press(switch: HandlebarSwitch)
}

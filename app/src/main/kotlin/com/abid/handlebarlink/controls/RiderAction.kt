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

    /** Speaks the primary device's battery in the helmet. */
    BATTERY,

    /** Nothing. */
    NOTHING,
}

/** A handlebar press, handed to whatever it is mapped to. */
fun interface ButtonPresses {
    /** [switch] was pressed. */
    fun press(switch: HandlebarSwitch)
}

package com.abid.handlebarlink.devices

import com.abid.handlebarlink.controls.RiderAction
import kotlinx.coroutines.flow.Flow

/** Whether Android sends the assistant button to this app. */
sealed interface AssistantHandler {
    /** It does. */
    data object ThisApp : AssistantHandler

    /** Android will ask which app, the next time it is pressed. */
    data object Ask : AssistantHandler

    /** Another app is set to get it ([label]). */
    data class Other(
        val label: String,
    ) : AssistantHandler
}

/**
 * The intercom's assistant button (research of 2026-11-18): a headset's voice-assistant button (HFP
 * AT+BVRA) makes Android start the app that handles `Intent.ACTION_VOICE_COMMAND`; this app is one, so the
 * rider can choose what the button does. Play, pause and skip from a headset go to the app playing audio
 * (CarLink) and cannot be taken over by another app.
 */
interface AssistantButton {
    /** What the button does, as the rider chose. */
    val action: Flow<RiderAction>

    /** Sets what the button does. */
    suspend fun choose(action: RiderAction)

    /** Who gets the button now. */
    fun handler(): AssistantHandler
}

package com.abid.handlebarlink.ui.handlebar

import androidx.compose.runtime.Immutable
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.RiderAction

/**
 * A key's callout on the switch: its press's [action], its hold's when that is called out too ([hold]), and
 * whether it is the call key's brand chip ([call]).
 */
@Immutable
internal data class KeyCallout(
    val key: SwitchKey,
    val action: RiderAction,
    val hold: RiderAction? = null,
    val call: Boolean = false,
)

/**
 * The callouts for [situation] (Paper "Handlebar — Smart buttons"). Riding: every key's press, as before. While
 * a call rings or is on: only the keys that do something, each with its hold on a second line; a key that answers,
 * declines, ends or mutes the call is the brand chip ("Answer · Hold to decline").
 */
internal fun keyCallouts(
    situation: CallSituation,
    presses: (HandlebarSwitch) -> RiderAction,
): List<KeyCallout> =
    SwitchKey.entries.mapNotNull { key ->
        val press = presses(key.press)
        if (situation == CallSituation.RIDING) return@mapNotNull KeyCallout(key, press)
        val hold = key.hold?.let(presses)?.takeIf { it != RiderAction.NOTHING }
        if (press == RiderAction.NOTHING && hold == null) return@mapNotNull null
        KeyCallout(key, press, hold, call = press in CALL_ACTIONS || hold in CALL_ACTIONS)
    }

private val CALL_ACTIONS =
    setOf(RiderAction.ANSWER_CALL, RiderAction.DECLINE_CALL, RiderAction.END_CALL, RiderAction.MUTE_MIC)

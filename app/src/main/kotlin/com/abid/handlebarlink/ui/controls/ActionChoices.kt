package com.abid.handlebarlink.ui.controls

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.ActionAvailability
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.controls.Unavailable
import com.abid.handlebarlink.controls.allowedActions

/**
 * One choice of an action chooser: the [action], why it can never work on this phone ([unavailable], null when it
 * can: only muting the microphone, when Android does not let the app change it) and whether it may be chosen at all
 * ([choosable]).
 */
@Immutable
data class ActionChoice(
    val action: RiderAction,
    val unavailable: Unavailable? = null,
    val choosable: Boolean = true,
)

/** The handlebar choices in [situation] ([allowedActions]), each with what [availability] says of it. */
fun handlebarChoices(
    situation: CallSituation,
    availability: ActionAvailability,
): List<ActionChoice> = allowedActions(situation).map { ActionChoice(it, availability.whyUnavailable(it)) }

/**
 * The intercom's assistant button: the riding actions, Siri included (the button's handler does it through our own
 * CarPlay, as a handlebar press: AssistantButtonActivity, RiderActions).
 */
val ASSISTANT_CHOICES: List<ActionChoice> = allowedActions(CallSituation.RIDING).map { ActionChoice(it) }

/** A heading of the chooser; the choices come in this order (Paper "Handlebar 5": Call, Sound, then Nothing). */
enum class ActionGroup(
    @param:StringRes val label: Int,
) {
    CALL(R.string.action_group_call),
    MUSIC(R.string.action_group_music),
    SOUND(R.string.action_group_sound),
    VOICE(R.string.action_group_voice),
    SCREEN(R.string.action_group_screen),
}

/** The group [action] is listed under; null for [RiderAction.NOTHING], which stands alone at the end. */
fun actionGroup(action: RiderAction): ActionGroup? =
    when (action) {
        RiderAction.ANSWER_CALL, RiderAction.DECLINE_CALL, RiderAction.END_CALL, RiderAction.MUTE_MIC -> {
            ActionGroup.CALL
        }

        RiderAction.PLAY_PAUSE, RiderAction.NEXT, RiderAction.PREVIOUS -> {
            ActionGroup.MUSIC
        }

        RiderAction.VOLUME_UP, RiderAction.VOLUME_DOWN, RiderAction.MUTE -> {
            ActionGroup.SOUND
        }

        RiderAction.SIRI, RiderAction.BATTERY, RiderAction.TIME -> {
            ActionGroup.VOICE
        }

        RiderAction.BRIGHTNESS_UP, RiderAction.BRIGHTNESS_DOWN, RiderAction.SWITCH_SCREEN,
        RiderAction.OPEN_CARPLAY, RiderAction.OPEN_APP,
        -> {
            ActionGroup.SCREEN
        }

        RiderAction.NOTHING -> {
            null
        }
    }

/** [choices] under their headings, in [ActionGroup] order, empty groups left out. */
fun groupedChoices(choices: List<ActionChoice>): List<Pair<ActionGroup, List<ActionChoice>>> {
    val byGroup = choices.groupBy { actionGroup(it.action) }
    return ActionGroup.entries.mapNotNull { group -> byGroup[group]?.let { group to it } }
}

/**
 * The action shown in the danger colour: hanging up (Paper "Handlebar 4/5"; declining a ring stays muted, as in
 * "Handlebar 3").
 */
fun isDestructive(action: RiderAction): Boolean = action == RiderAction.END_CALL

package com.abid.handlebarlink.controls

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** What each handlebar press does, in each [CallSituation], as the rider set it (the Handlebar page). */
interface HandlebarMapping {
    /** Every press's action in every situation, now; always complete (a press never set has its default). */
    val current: StateFlow<Map<CallSituation, Map<HandlebarSwitch, RiderAction>>>

    /**
     * Makes [switch] do [action] in [situation]. Returns false and stores nothing when [situation] does not
     * offer [action] ([allowedActions]).
     */
    suspend fun assign(
        situation: CallSituation,
        switch: HandlebarSwitch,
        action: RiderAction,
    ): Boolean

    /** Every situation back to [HANDLEBAR_DEFAULTS]. */
    suspend fun reset()
}

/**
 * The defaults. The presses the phone receives (SAB_PROTOCOL.md "Verified": hold up sends nothing, hold right
 * stays with the meter). Riding: what they did before the rider could change them (up previous, down next,
 * right play/pause, hold down play/pause, left nothing) and hold left Siri. Calls live on the left key, which
 * has a phone symbol printed on it on the CB350 (owner's UX review, 2026-11-19): ringing, left answers and hold
 * left declines; on a call, left mutes the microphone, hold left ends the call, up and down set the call's
 * volume. Every other press does nothing then. Any press may be set to any of its situation's actions.
 */
val HANDLEBAR_DEFAULTS: Map<CallSituation, Map<HandlebarSwitch, RiderAction>> =
    mapOf(
        CallSituation.RIDING to
            mapOf(
                HandlebarSwitch.UP to RiderAction.PREVIOUS,
                HandlebarSwitch.DOWN to RiderAction.NEXT,
                HandlebarSwitch.LEFT to RiderAction.NOTHING,
                HandlebarSwitch.RIGHT to RiderAction.PLAY_PAUSE,
                HandlebarSwitch.HOLD_DOWN to RiderAction.PLAY_PAUSE,
                HandlebarSwitch.HOLD_LEFT to RiderAction.SIRI,
            ),
        CallSituation.RINGING to
            mapOf(
                HandlebarSwitch.UP to RiderAction.NOTHING,
                HandlebarSwitch.DOWN to RiderAction.NOTHING,
                HandlebarSwitch.LEFT to RiderAction.ANSWER_CALL,
                HandlebarSwitch.RIGHT to RiderAction.NOTHING,
                HandlebarSwitch.HOLD_DOWN to RiderAction.NOTHING,
                HandlebarSwitch.HOLD_LEFT to RiderAction.DECLINE_CALL,
            ),
        CallSituation.ON_CALL to
            mapOf(
                HandlebarSwitch.UP to RiderAction.VOLUME_UP,
                HandlebarSwitch.DOWN to RiderAction.VOLUME_DOWN,
                HandlebarSwitch.LEFT to RiderAction.MUTE_MIC,
                HandlebarSwitch.RIGHT to RiderAction.NOTHING,
                HandlebarSwitch.HOLD_DOWN to RiderAction.NOTHING,
                HandlebarSwitch.HOLD_LEFT to RiderAction.END_CALL,
            ),
    )

/**
 * The actions [situation] offers, in [RiderAction]'s order (the Handlebar page's choices). Riding: everything
 * but the call actions. Ringing and on a call: what works on the phone without disturbing the call (volume,
 * brightness, screens, nothing) plus that situation's call actions: answer and decline while ringing, end and
 * mute microphone on a call. Left out of both: the music keys (they would pause or skip CarPlay's audio under
 * the call), the spoken battery and time (they would talk over the ring or the conversation), Mute (Android
 * keeps a call's volume above zero, and it is easily confused with Mute microphone) and Siri (a stray hold
 * would start Siri over the conversation; ending, muting and volume are what a call needs).
 */
fun allowedActions(situation: CallSituation): List<RiderAction> =
    RiderAction.entries.filter {
        it in
            ALLOWED.getValue(situation)
    }

/** Whether [situation] offers [action] ([allowedActions]). */
fun CallSituation.allows(action: RiderAction): Boolean = action in ALLOWED.getValue(this)

private val CALL_ONLY =
    setOf(RiderAction.ANSWER_CALL, RiderAction.DECLINE_CALL, RiderAction.END_CALL, RiderAction.MUTE_MIC)

private val DURING_CALLS =
    setOf(
        RiderAction.VOLUME_UP,
        RiderAction.VOLUME_DOWN,
        RiderAction.BRIGHTNESS_UP,
        RiderAction.BRIGHTNESS_DOWN,
        RiderAction.SWITCH_SCREEN,
        RiderAction.OPEN_CARPLAY,
        RiderAction.OPEN_APP,
        RiderAction.NOTHING,
    )

private val ALLOWED: Map<CallSituation, Set<RiderAction>> =
    mapOf(
        CallSituation.RIDING to RiderAction.entries.toSet() - CALL_ONLY,
        CallSituation.RINGING to DURING_CALLS + RiderAction.ANSWER_CALL + RiderAction.DECLINE_CALL,
        CallSituation.ON_CALL to DURING_CALLS + RiderAction.END_CALL + RiderAction.MUTE_MIC,
    )

/**
 * The mapping from what is stored (action names by situation and press): a press never set, set to an unknown
 * name, or set to an action its situation does not offer, keeps its default.
 */
fun mappingFrom(
    stored: Map<CallSituation, Map<HandlebarSwitch, String?>>,
): Map<CallSituation, Map<HandlebarSwitch, RiderAction>> =
    HANDLEBAR_DEFAULTS.mapValues { (situation, defaults) ->
        defaults.mapValues { (switch, default) ->
            RiderAction.entries.firstOrNull { it.name == stored[situation]?.get(switch) && situation.allows(it) }
                ?: default
        }
    }

/**
 * The DataStore key of a press in a situation. Riding keeps the keys from before situations existed
 * (`handlebar_UP`), so riders keep their settings; ringing and on a call have their own.
 */
internal fun mappingKey(
    situation: CallSituation,
    switch: HandlebarSwitch,
): String =
    when (situation) {
        CallSituation.RIDING -> "handlebar_${switch.name}"
        CallSituation.RINGING -> "handlebar_ringing_${switch.name}"
        CallSituation.ON_CALL -> "handlebar_call_${switch.name}"
    }

/** [HandlebarMapping] in the app's DataStore, one key per situation and press; read once into [current]. */
@Singleton
class DataStoreHandlebarMapping
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
        @ApplicationScope scope: CoroutineScope,
        logger: Logger,
    ) : HandlebarMapping {
        private val log = logger.child("control")

        override val current: StateFlow<Map<CallSituation, Map<HandlebarSwitch, RiderAction>>> =
            store.data
                .map { preferences ->
                    mappingFrom(
                        CallSituation.entries.associateWith { situation ->
                            HandlebarSwitch.entries.associateWith { preferences[key(situation, it)] }
                        },
                    )
                }.stateIn(scope, SharingStarted.Eagerly, HANDLEBAR_DEFAULTS)

        override suspend fun assign(
            situation: CallSituation,
            switch: HandlebarSwitch,
            action: RiderAction,
        ): Boolean {
            if (!situation.allows(action)) {
                log.warn("control.mapping.rejected", "situation" to situation, "switch" to switch, "action" to action)
                return false
            }
            store.edit { it[key(situation, switch)] = action.name }
            return true
        }

        override suspend fun reset() {
            store.edit { preferences ->
                CallSituation.entries.forEach { situation ->
                    HandlebarSwitch.entries.forEach { preferences.remove(key(situation, it)) }
                }
            }
        }

        private fun key(
            situation: CallSituation,
            switch: HandlebarSwitch,
        ) = stringPreferencesKey(mappingKey(situation, switch))
    }

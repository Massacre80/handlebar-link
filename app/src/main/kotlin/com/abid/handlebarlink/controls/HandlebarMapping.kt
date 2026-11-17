package com.abid.handlebarlink.controls

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** What each handlebar press does, as the rider set it (Home's Handlebar card). */
interface HandlebarMapping {
    /** Every press's action, now. */
    val current: StateFlow<Map<HandlebarSwitch, RiderAction>>

    /** Makes [switch] do [action]. */
    suspend fun assign(
        switch: HandlebarSwitch,
        action: RiderAction,
    )

    /** Back to [HANDLEBAR_DEFAULTS]. */
    suspend fun reset()
}

/**
 * The presses the phone receives (SAB_PROTOCOL.md "Verified": hold up sends nothing, hold right stays with
 * the meter) and what they did before the rider could change them: up previous, down next, right
 * play/pause, hold down play/pause; left and hold left free.
 */
val HANDLEBAR_DEFAULTS: Map<HandlebarSwitch, RiderAction> =
    mapOf(
        HandlebarSwitch.UP to RiderAction.PREVIOUS,
        HandlebarSwitch.DOWN to RiderAction.NEXT,
        HandlebarSwitch.LEFT to RiderAction.NOTHING,
        HandlebarSwitch.RIGHT to RiderAction.PLAY_PAUSE,
        HandlebarSwitch.HOLD_DOWN to RiderAction.PLAY_PAUSE,
        HandlebarSwitch.HOLD_LEFT to RiderAction.NOTHING,
    )

/** The mapping from what is stored (by name): a press never set, or set to an unknown name, keeps its default. */
fun mappingFrom(stored: Map<HandlebarSwitch, String?>): Map<HandlebarSwitch, RiderAction> =
    HANDLEBAR_DEFAULTS.mapValues { (switch, default) ->
        RiderAction.entries.firstOrNull { it.name == stored[switch] } ?: default
    }

/** [HandlebarMapping] in the app's DataStore, one key per press; read once into [current] for the ride. */
@Singleton
class DataStoreHandlebarMapping
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
        @ApplicationScope scope: CoroutineScope,
    ) : HandlebarMapping {
        override val current: StateFlow<Map<HandlebarSwitch, RiderAction>> =
            store.data
                .map { preferences -> mappingFrom(HANDLEBAR_DEFAULTS.keys.associateWith { preferences[key(it)] }) }
                .stateIn(scope, SharingStarted.Eagerly, HANDLEBAR_DEFAULTS)

        override suspend fun assign(
            switch: HandlebarSwitch,
            action: RiderAction,
        ) {
            store.edit { it[key(switch)] = action.name }
        }

        override suspend fun reset() {
            store.edit { preferences -> HANDLEBAR_DEFAULTS.keys.forEach { preferences.remove(key(it)) } }
        }

        private fun key(switch: HandlebarSwitch) = stringPreferencesKey("handlebar_${switch.name}")
    }

package com.abid.handlebarlink.carplay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The "Handlebar Link CarPlay (beta)" switch (our own receiver instead of CarLink; off until the rider turns it on) and
 * the "CarPlay screen" setting (landscape until the rider chooses portrait).
 */
interface CarPlaySettings {
    /** Whether our own CarPlay receiver may run during rides. */
    val enabled: Flow<Boolean>

    /** Turns our own receiver on or off. */
    suspend fun setEnabled(enabled: Boolean)

    /** Which way CarPlay's picture stands ([CarPlayOrientation.DEFAULT] until chosen). */
    val orientation: Flow<CarPlayOrientation>

    /** The rider chose [orientation]; a running CarPlay reconnects to apply it (CarPlayCoordinator). */
    suspend fun setOrientation(orientation: CarPlayOrientation)
}

/** [CarPlaySettings] in the app's settings DataStore, next to the other settings (di/DataModule). */
class DataStoreCarPlaySettings
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : CarPlaySettings {
        override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

        override suspend fun setEnabled(enabled: Boolean) {
            store.edit { it[ENABLED] = enabled }
        }

        /** Stored by name; a name this version does not know reads as the default. */
        override val orientation: Flow<CarPlayOrientation> =
            store.data.map { prefs ->
                CarPlayOrientation.entries.firstOrNull { it.name == prefs[ORIENTATION] } ?: CarPlayOrientation.DEFAULT
            }

        override suspend fun setOrientation(orientation: CarPlayOrientation) {
            store.edit { it[ORIENTATION] = orientation.name }
        }

        private companion object {
            val ENABLED = booleanPreferencesKey("carplay_beta")
            val ORIENTATION = stringPreferencesKey("carplay_orientation")
        }
    }

/**
 * Whether the CarPlay display has shown its "Swipe back for Handlebar Link" hint: once, the first time it opens. In the
 * app's settings DataStore.
 */
class CarPlayDisplayHint
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) {
        /** True once the hint has been shown. */
        val shown: Flow<Boolean> = store.data.map { it[SHOWN] ?: false }

        /** The hint was shown; it is not shown again. */
        suspend fun markShown() {
            store.edit { it[SHOWN] = true }
        }

        private companion object {
            val SHOWN = booleanPreferencesKey("carplay_display_hint_shown")
        }
    }

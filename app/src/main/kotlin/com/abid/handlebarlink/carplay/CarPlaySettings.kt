package com.abid.handlebarlink.carplay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The "Handlebar Link CarPlay (beta)" switch: our own receiver instead of CarLink. Off until the rider turns it on. */
interface CarPlaySettings {
    /** Whether our own CarPlay receiver may run during rides. */
    val enabled: Flow<Boolean>

    /** Turns our own receiver on or off. */
    suspend fun setEnabled(enabled: Boolean)
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

        private companion object {
            val ENABLED = booleanPreferencesKey("carplay_beta")
        }
    }

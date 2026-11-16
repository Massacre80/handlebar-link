package com.abid.handlebarlink.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** [BikeSettings] in Preferences DataStore (asynchronous, unlike SharedPreferences; research 01 §1). */
class DataStoreBikeSettings
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : BikeSettings {
        override val bike: Flow<SelectedBike?> =
            store.data.map { preferences ->
                preferences[ADDRESS]?.let { SelectedBike(it, preferences[NAME] ?: it) }
            }

        override suspend fun select(bike: SelectedBike) {
            store.edit {
                it[ADDRESS] = bike.address
                it[NAME] = bike.name
            }
        }

        override suspend fun forget() {
            store.edit {
                it.remove(ADDRESS)
                it.remove(NAME)
            }
        }

        private companion object {
            val ADDRESS = stringPreferencesKey("bike_address")
            val NAME = stringPreferencesKey("bike_name")
        }
    }

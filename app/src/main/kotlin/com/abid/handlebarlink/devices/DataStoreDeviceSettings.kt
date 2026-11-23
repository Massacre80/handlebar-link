package com.abid.handlebarlink.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [DeviceSettings] in Preferences DataStore: the ordered addresses in one key, each device's kind and name in
 * its own keys (no serialisation library needed, no separator a name could contain).
 */
class DataStoreDeviceSettings
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : DeviceSettings {
        override val devices: Flow<List<SavedDevice>> =
            store.data.map { preferences ->
                addresses(preferences).mapNotNull { address ->
                    val name = preferences[nameKey(address)] ?: return@mapNotNull null
                    val kind =
                        DeviceKind.entries.firstOrNull { it.name == preferences[kindKey(address)] } ?: DeviceKind.OTHER
                    SavedDevice(address, name, kind, preferences[modelKey(address)] ?: name)
                }
            }

        override suspend fun save(device: SavedDevice) {
            store.edit { preferences ->
                val addresses = addresses(preferences)
                if (device.address !in
                    addresses
                ) {
                    preferences[ORDER] = (addresses + device.address).joinToString(SEPARATOR)
                }
                preferences[nameKey(device.address)] = device.name
                preferences[kindKey(device.address)] = device.kind.name
                preferences[modelKey(device.address)] = device.model
            }
        }

        override val primary: Flow<String?> = store.data.map { it[PRIMARY] }

        override suspend fun choosePrimary(address: String) {
            store.edit { it[PRIMARY] = address }
        }

        override val calls: Flow<String?> = store.data.map { it[CALLS] }

        override suspend fun chooseCalls(route: String) {
            store.edit { it[CALLS] = route }
        }

        override val music: Flow<String?> = store.data.map { it[MUSIC] }

        override suspend fun chooseMusic(route: String) {
            store.edit { it[MUSIC] = route }
        }

        override suspend fun remove(address: String) {
            store.edit { preferences ->
                preferences[ORDER] = (addresses(preferences) - address).joinToString(SEPARATOR)
                preferences.remove(nameKey(address))
                preferences.remove(kindKey(address))
                preferences.remove(modelKey(address))
                if (preferences[PRIMARY] == address) preferences.remove(PRIMARY)
                if (preferences[CALLS] == address) preferences.remove(CALLS)
                if (preferences[MUSIC] == address) preferences.remove(MUSIC)
            }
        }

        private fun addresses(preferences: Preferences): List<String> =
            preferences[ORDER].orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }

        private companion object {
            /** Bluetooth addresses never contain a space. */
            const val SEPARATOR = " "
            val ORDER = stringPreferencesKey("devices")
            val PRIMARY = stringPreferencesKey("device_primary")
            val CALLS = stringPreferencesKey("device_calls")
            val MUSIC = stringPreferencesKey("device_music")

            fun nameKey(address: String) = stringPreferencesKey("device_name_$address")

            fun kindKey(address: String) = stringPreferencesKey("device_kind_$address")

            fun modelKey(address: String) = stringPreferencesKey("device_model_$address")
        }
    }

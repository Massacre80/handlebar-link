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

        override suspend fun remove(address: String) {
            store.edit { preferences ->
                preferences[ORDER] = (addresses(preferences) - address).joinToString(SEPARATOR)
                preferences.remove(nameKey(address))
                preferences.remove(kindKey(address))
                preferences.remove(modelKey(address))
            }
        }

        private fun addresses(preferences: Preferences): List<String> =
            preferences[ORDER].orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }

        private companion object {
            /** Bluetooth addresses never contain a space. */
            const val SEPARATOR = " "
            val ORDER = stringPreferencesKey("devices")

            fun nameKey(address: String) = stringPreferencesKey("device_name_$address")

            fun kindKey(address: String) = stringPreferencesKey("device_kind_$address")

            fun modelKey(address: String) = stringPreferencesKey("device_model_$address")
        }
    }

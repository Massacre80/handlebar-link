package com.abid.handlebarlink.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The app's look; the rider chooses it, the app never switches by itself (design decision 2026-11-17). */
enum class AppTheme {
    /** Dark, bike photo as hero, glass cards. */
    NIGHT,

    /** Light, card grid. */
    BENTO,
}

/** The rider's appearance choices, in the app's settings DataStore. */
class AppearanceSettings
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) {
        /** The chosen theme; Night until the rider picks one (or after an unknown stored value). */
        val theme: Flow<AppTheme> =
            store.data.map { preferences ->
                AppTheme.entries.firstOrNull { it.name == preferences[THEME] } ?: AppTheme.NIGHT
            }

        /** Stores [theme]. */
        suspend fun setTheme(theme: AppTheme) {
            store.edit { it[THEME] = theme.name }
        }

        private companion object {
            val THEME = stringPreferencesKey("theme")
        }
    }

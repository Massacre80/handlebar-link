package com.abid.handlebarlink.devices

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.abid.handlebarlink.controls.RiderAction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [AssistantButton]: the choice in the app's DataStore; who gets `ACTION_VOICE_COMMAND` from the package
 * manager's default resolution (the manifest's <queries> lets the app see the other handlers): this app, the
 * system's chooser (no default yet), or another app set as the default.
 */
class AndroidAssistantButton
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val store: DataStore<Preferences>,
    ) : AssistantButton {
        override val action: Flow<RiderAction> =
            store.data.map { preferences ->
                RiderAction.entries.firstOrNull { it.name == preferences[ACTION] } ?: RiderAction.PLAY_PAUSE
            }

        override suspend fun choose(action: RiderAction) {
            store.edit { it[ACTION] = action.name }
        }

        override fun handler(): AssistantHandler {
            val packages = context.packageManager
            val resolved =
                packages
                    .resolveActivity(Intent(Intent.ACTION_VOICE_COMMAND), PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo
            return when (resolved?.packageName) {
                context.packageName -> AssistantHandler.ThisApp
                null, SYSTEM -> AssistantHandler.Ask
                else -> AssistantHandler.Other(resolved.applicationInfo.loadLabel(packages).toString())
            }
        }

        private companion object {
            val ACTION = stringPreferencesKey("assistant_button")

            /** The system's chooser ("android") resolves when no app is the default. */
            const val SYSTEM = "android"
        }
    }

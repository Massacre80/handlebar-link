package com.abid.handlebarlink.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppearanceSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `Night until chosen, then the chosen theme, and an unknown value falls back to Night`() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(scope = backgroundScope) {
                    folder.newFile("settings.preferences_pb").also { it.delete() }
                }
            val settings = AppearanceSettings(store)

            assertThat(settings.theme.first()).isEqualTo(AppTheme.NIGHT)
            settings.setTheme(AppTheme.BENTO)
            assertThat(settings.theme.first()).isEqualTo(AppTheme.BENTO)
            store.edit { it[stringPreferencesKey("theme")] = "SUNLIGHT" }
            assertThat(settings.theme.first()).isEqualTo(AppTheme.NIGHT)
        }

    @Test
    fun `the theme and the bike share one store without erasing each other`() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(scope = backgroundScope) {
                    folder.newFile("settings.preferences_pb").also { it.delete() }
                }
            val appearance = AppearanceSettings(store)
            val bikes = DataStoreBikeSettings(store)
            appearance.setTheme(AppTheme.BENTO)
            bikes.select(SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040"))

            bikes.forget()

            assertThat(appearance.theme.first()).isEqualTo(AppTheme.BENTO)
        }
}

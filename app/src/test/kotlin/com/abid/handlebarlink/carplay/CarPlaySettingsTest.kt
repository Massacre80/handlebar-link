package com.abid.handlebarlink.carplay

import android.Manifest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.data.AppearanceSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarPlaySettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the CarPlay screen shares the settings store with the app's other settings`() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(scope = backgroundScope) {
                    folder.newFile("settings.preferences_pb").also { it.delete() }
                }
            val settings = DataStoreCarPlaySettings(store)
            val appearance = AppearanceSettings(store)
            appearance.setTheme(AppTheme.BENTO)

            settings.setOrientation(CarPlayOrientation.PORTRAIT)

            assertThat(settings.orientation.first()).isEqualTo(CarPlayOrientation.PORTRAIT)
            assertThat(appearance.theme.first()).isEqualTo(AppTheme.BENTO)
        }

    @Test
    fun `the CarPlay screen is landscape until portrait is chosen, is stored, and an unknown value reads landscape`() =
        runTest {
            val file = folder.newFile("screen.preferences_pb").also { it.delete() }
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val settings = DataStoreCarPlaySettings(store)

            assertThat(settings.orientation.first()).isEqualTo(CarPlayOrientation.LANDSCAPE)
            settings.setOrientation(CarPlayOrientation.PORTRAIT)
            assertThat(settings.orientation.first()).isEqualTo(CarPlayOrientation.PORTRAIT)
            store.edit { it[stringPreferencesKey("carplay_orientation")] = "UPSIDE_DOWN" }
            assertThat(settings.orientation.first()).isEqualTo(CarPlayOrientation.LANDSCAPE)
            settings.setOrientation(CarPlayOrientation.LANDSCAPE)
            assertThat(settings.orientation.first()).isEqualTo(CarPlayOrientation.LANDSCAPE)
        }

    @Test
    fun `the CarPlay display's hint is due until it was shown once, and that is stored`() =
        runTest {
            val file = folder.newFile("hint.preferences_pb").also { it.delete() }
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
            val hint = CarPlayDisplayHint(store)

            assertThat(hint.shown.first()).isFalse()
            hint.markShown()

            assertThat(hint.shown.first()).isTrue()
            assertThat(DataStoreCarPlaySettings(store).orientation.first()).isEqualTo(CarPlayOrientation.LANDSCAPE)
        }

    @Test
    fun `CarPlay's permissions follow the Android release`() {
        // Android 12/12L: Wi-Fi Direct needs precise location, asked with approximate (Android 12 ignores a request
        // for precise alone, location permissions guide); 13+: Nearby Wi-Fi; 17+: local network as well; the
        // microphone for Siri and calls on every release (C5).
        val microphone = Manifest.permission.RECORD_AUDIO
        val location = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertThat(carPlayPermissions(31))
            .containsExactly(Manifest.permission.BLUETOOTH_CONNECT, *location, microphone)
        assertThat(carPlayPermissions(32))
            .containsExactly(Manifest.permission.BLUETOOTH_CONNECT, *location, microphone)
        assertThat(carPlayPermissions(33))
            .containsExactly(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES, microphone)
        assertThat(carPlayPermissions(36))
            .containsExactly(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES, microphone)
        assertThat(carPlayPermissions(37)).containsExactly(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            "android.permission.ACCESS_LOCAL_NETWORK",
            microphone,
        )
    }

    @Test
    fun `the status puts the ports first, then a failure, the permissions, the rider's stop, and the engine last`() {
        val state = CarPlayState.Idle
        val missing = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)

        assertThat(carPlayStatus(false, missing, true, state, held = true)).isEqualTo(CarPlayStatus.NotAvailable)
        assertThat(carPlayStatus(true, missing, true, state, held = true)).isEqualTo(CarPlayStatus.Failed)
        assertThat(carPlayStatus(true, missing, false, state, held = true))
            .isEqualTo(CarPlayStatus.NeedsPermission(missing))
        assertThat(carPlayStatus(true, emptyList(), false, state, held = true)).isEqualTo(CarPlayStatus.Held)
        assertThat(carPlayStatus(true, emptyList(), false, state)).isEqualTo(CarPlayStatus.Engine(state))
    }
}

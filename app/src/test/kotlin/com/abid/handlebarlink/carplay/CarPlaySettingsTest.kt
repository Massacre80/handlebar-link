package com.abid.handlebarlink.carplay

import android.Manifest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
    fun `the beta switch is off until turned on, is stored, and shares the settings store`() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(scope = backgroundScope) {
                    folder.newFile("settings.preferences_pb").also { it.delete() }
                }
            val settings = DataStoreCarPlaySettings(store)
            val appearance = AppearanceSettings(store)
            appearance.setTheme(AppTheme.BENTO)

            assertThat(settings.enabled.first()).isFalse()
            settings.setEnabled(true)
            assertThat(settings.enabled.first()).isTrue()
            settings.setEnabled(false)
            assertThat(settings.enabled.first()).isFalse()
            assertThat(appearance.theme.first()).isEqualTo(AppTheme.BENTO)
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
            assertThat(DataStoreCarPlaySettings(store).enabled.first()).isFalse()
        }

    @Test
    fun `CarPlay's permissions follow the Android release`() {
        // Android 12/12L: Wi-Fi Direct needs precise location; 13+: Nearby Wi-Fi; 17+: local network as well.
        assertThat(carPlayPermissions(31))
            .containsExactly(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(carPlayPermissions(33))
            .containsExactly(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
        assertThat(carPlayPermissions(36))
            .containsExactly(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
        assertThat(carPlayPermissions(37)).containsExactly(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            "android.permission.ACCESS_LOCAL_NETWORK",
        )
    }

    @Test
    fun `the status puts the switch first, then the ports, a failure, the permissions, and the engine last`() {
        val state = CarPlayState.Idle
        val missing = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)

        assertThat(carPlayStatus(false, false, missing, true, state)).isEqualTo(CarPlayStatus.Off)
        assertThat(carPlayStatus(true, false, missing, true, state)).isEqualTo(CarPlayStatus.NotAvailable)
        assertThat(carPlayStatus(true, true, missing, true, state)).isEqualTo(CarPlayStatus.Failed)
        assertThat(carPlayStatus(true, true, missing, false, state)).isEqualTo(CarPlayStatus.NeedsPermission(missing))
        assertThat(carPlayStatus(true, true, emptyList(), false, state)).isEqualTo(CarPlayStatus.Engine(state))
    }
}

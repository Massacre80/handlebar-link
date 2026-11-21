package com.abid.handlebarlink.ui.settings

import android.Manifest
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isNull
import com.abid.handlebarlink.ui.permissions.Access
import org.junit.Test

/**
 * Settings › Permissions and Allow all, which asks for each row's [runtime] permissions. The optional location row
 * forwards the bike's GPS to the iPhone (C6 location); CarPlay's Wi-Fi Direct also needs location on Android 12/12L.
 */
class SettingsPermissionTest {
    private val location = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Test
    fun `the rows are Nearby devices, notifications, the two Android settings, CarPlay and location`() {
        assertThat(SettingsPermission.entries.toList()).containsExactly(
            SettingsPermission.NEARBY,
            SettingsPermission.NOTIFICATIONS,
            SettingsPermission.OVERLAY,
            SettingsPermission.BRIGHTNESS,
            SettingsPermission.CARPLAY,
            SettingsPermission.LOCATION,
        )
    }

    @Test
    fun `from Android 13 only the location row asks for location`() {
        for (sdk in ANDROID_13..ANDROID_17) {
            val asking = SettingsPermission.entries.filter { row -> row.runtime(sdk).orEmpty().any { it in location } }
            assertThat(asking, "API $sdk").containsExactly(SettingsPermission.LOCATION)
        }
    }

    @Test
    fun `on Android 12 and 12L both CarPlay (Wi-Fi Direct) and the location row ask for location`() {
        for (sdk in ANDROID_12..ANDROID_12L) {
            val asking = SettingsPermission.entries.filter { row -> row.runtime(sdk).orEmpty().any { it in location } }
            assertThat(asking, "API $sdk").containsExactly(SettingsPermission.CARPLAY, SettingsPermission.LOCATION)
        }
    }

    @Test
    fun `the two Android settings open their own screens instead of a runtime request`() {
        assertThat(SettingsPermission.OVERLAY.runtime(ANDROID_17)).isNull()
        assertThat(SettingsPermission.BRIGHTNESS.runtime(ANDROID_17)).isNull()
    }

    @Test
    fun `each row shows its own status`() {
        val access =
            SettingsAccess(Access.GRANTED, Access.ASK, Access.BLOCKED, Access.GRANTED, Access.ASK, Access.BLOCKED)

        assertThat(access.all())
            .containsExactly(Access.GRANTED, Access.ASK, Access.BLOCKED, Access.GRANTED, Access.ASK, Access.BLOCKED)
    }

    private companion object {
        const val ANDROID_12 = 31
        const val ANDROID_12L = 32
        const val ANDROID_13 = 33
        const val ANDROID_17 = 37
    }
}

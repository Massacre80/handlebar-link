package com.abid.handlebarlink.ui.settings

import android.Manifest
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isNull
import com.abid.handlebarlink.ui.permissions.Access
import org.junit.Test

/**
 * Settings › Permissions and Allow all, which asks for each row's [runtime] permissions: no location since ride
 * logging was dropped (owner, 2026-11-20), except what CarPlay's Wi-Fi Direct needs on Android 12/12L.
 */
class SettingsPermissionTest {
    private val location = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Test
    fun `the rows are Nearby devices, notifications, the two Android settings and CarPlay, without location`() {
        assertThat(SettingsPermission.entries.toList()).containsExactly(
            SettingsPermission.NEARBY,
            SettingsPermission.NOTIFICATIONS,
            SettingsPermission.OVERLAY,
            SettingsPermission.BRIGHTNESS,
            SettingsPermission.CARPLAY,
        )
    }

    @Test
    fun `from Android 13 neither a row nor Allow all asks for location`() {
        for (sdk in ANDROID_13..ANDROID_17) {
            val asked = SettingsPermission.entries.flatMap { it.runtime(sdk).orEmpty() }
            assertThat(asked.filter { it in location }, "API $sdk").isEmpty()
        }
    }

    @Test
    fun `on Android 12 and 12L only CarPlay's row asks for location, for Wi-Fi Direct`() {
        for (sdk in ANDROID_12..ANDROID_12L) {
            val asking = SettingsPermission.entries.filter { row -> row.runtime(sdk).orEmpty().any { it in location } }
            assertThat(asking, "API $sdk").containsExactly(SettingsPermission.CARPLAY)
        }
    }

    @Test
    fun `the two Android settings open their own screens instead of a runtime request`() {
        assertThat(SettingsPermission.OVERLAY.runtime(ANDROID_17)).isNull()
        assertThat(SettingsPermission.BRIGHTNESS.runtime(ANDROID_17)).isNull()
    }

    @Test
    fun `each row shows its own status`() {
        val access = SettingsAccess(Access.GRANTED, Access.ASK, Access.BLOCKED, Access.GRANTED, Access.ASK)

        assertThat(access.all()).containsExactly(Access.GRANTED, Access.ASK, Access.BLOCKED, Access.GRANTED, Access.ASK)
    }

    private companion object {
        const val ANDROID_12 = 31
        const val ANDROID_12L = 32
        const val ANDROID_13 = 33
        const val ANDROID_17 = 37
    }
}

package com.abid.handlebarlink.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.permissions.PermissionItem
import com.abid.handlebarlink.ui.permissions.PermissionList

/**
 * Settings › Permissions: what each one is for, and its call to action (the white pill) until it is allowed.
 * [onAct] asks for permission [SettingsAccess] index 0–3 (nearby, notifications, notification access,
 * location), or opens Android's settings page for one refused for good.
 */
@Composable
fun PermissionsScreen(
    access: SettingsAccess,
    onAct: (Int) -> Unit,
    onBack: () -> Unit,
) {
    SubPage(stringResource(R.string.settings_permissions), onBack) {
        HlText(stringResource(R.string.permissions_intro), HlTheme.typography.body, HlTheme.colors.inkMuted)
        PermissionList(
            ROWS.zip(access.all()).mapIndexed { index, (row, status) ->
                PermissionItem(row.first, row.second, status) { onAct(index) }
            },
        )
    }
}

private val ROWS =
    listOf(
        R.string.permission_nearby to R.string.permission_nearby_why,
        R.string.permission_notifications to R.string.permission_notifications_why,
        R.string.permission_media to R.string.permission_media_why,
        R.string.permission_location to R.string.permission_location_why,
    )

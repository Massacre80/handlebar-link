package com.abid.handlebarlink.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.PermissionItem
import com.abid.handlebarlink.ui.permissions.PermissionList

/**
 * Settings › Permissions: what each one is for, and its call to action (the white pill) until it is allowed.
 * [onAct] asks for the permission at [SettingsPermission] index (nearby, notifications, display over other apps, modify
 * system settings, and CarPlay), or opens Android's settings page for one refused for good; [onAllowAll] walks every
 * missing one.
 */
@Composable
fun PermissionsScreen(
    access: SettingsAccess,
    onAct: (Int) -> Unit,
    onBack: () -> Unit,
    onAllowAll: () -> Unit = {},
) {
    SubPage(stringResource(R.string.settings_permissions), onBack) {
        HlText(stringResource(R.string.permissions_intro), HlTheme.typography.body, HlTheme.colors.inkMuted)
        PermissionList(
            SettingsPermission.entries.map { row ->
                PermissionItem(row.title, row.why, access[row], onAct = { onAct(row.ordinal) })
            },
        )
        // While anything is missing, Allow all (under the list, what it will do below it) walks through it
        // (rememberAllowAll); gone once all are allowed.
        val missing = access.all().count { it != Access.GRANTED }
        if (missing > 0) {
            HlButton(
                pluralStringResource(R.plurals.permissions_allow_all, missing, missing),
                onAllowAll,
                Modifier.fillMaxWidth(),
                brand = true,
            )
            HlText(
                stringResource(R.string.permissions_allow_all_how),
                HlTheme.typography.body,
                HlTheme.colors.inkMuted,
            )
        }
    }
}

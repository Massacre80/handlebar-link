package com.abid.handlebarlink.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlIconButton
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlSectionLabel
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.data.SelectedBike
import com.abid.handlebarlink.ui.permissions.Access

/** Where the four permissions stand, and what tapping each does. */
data class SettingsAccess(
    /** Nearby devices (Bluetooth). */
    val nearby: Access,
    /** Notifications. */
    val notifications: Access,
    /** Notification access (CarLink's media session). */
    val media: Access,
    /** Location (recording rides). */
    val location: Access,
)

/** Settings' actions. */
data class SettingsActions(
    /** Open the Appearance page. */
    val onAppearance: () -> Unit,
    /** Choose a bike. */
    val onBike: (SelectedBike) -> Unit,
    /** Act on a permission: 0 Nearby devices, 1 notifications, 2 notification access, 3 location. */
    val onAccess: (Int) -> Unit,
    /** Forget the bike and run setup again (pairs a meter in the app). */
    val onSetup: () -> Unit,
    /** Back. */
    val onBack: () -> Unit,
)

/**
 * Settings (not in the design file; built from its components): the bike, the permissions it needs, and
 * the look. Rows sit on the screen colour, so they use the panel fill (the design's raisedLo is for sheets).
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    access: SettingsAccess,
    actions: SettingsActions,
) {
    SubPage(stringResource(R.string.settings), actions.onBack) {
        BikeSection(state, access.nearby, actions)
        PermissionsSection(access, actions.onAccess)
        HlValueRow(
            value = stringResource(R.string.settings_appearance),
            hint = stringResource(themeName(state.theme)),
            onClick = actions.onAppearance,
            container = HlTheme.colors.panel,
        )
    }
}

@Composable
private fun BikeSection(
    state: SettingsUiState,
    nearby: Access,
    actions: SettingsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        HlSectionLabel(stringResource(R.string.settings_bike))
        val meters = state.meters
        when {
            nearby != Access.GRANTED -> {
                HlValueRow(stringResource(R.string.bike_needs_nearby), hint = null, container = HlTheme.colors.panel)
            }

            meters.isNullOrEmpty() -> {
                HlValueRow(
                    stringResource(R.string.bike_none_paired),
                    hint = stringResource(R.string.bike_pair_hint),
                    onClick = actions.onSetup,
                    container = HlTheme.colors.panel,
                )
            }

            else -> {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
                    meters.forEach { meter ->
                        HlOptionRow(
                            icon = HlIcons.Bluetooth,
                            title = meter.name,
                            subtitle = meter.address,
                            selected = meter.address == state.bike?.address,
                            onClick = { actions.onBike(meter) },
                            container = HlTheme.colors.panel,
                        )
                    }
                }
            }
        }
        if (nearby == Access.GRANTED && !meters.isNullOrEmpty()) {
            HlValueRow(
                stringResource(R.string.settings_setup_again),
                hint = null,
                onClick = actions.onSetup,
                container = HlTheme.colors.panel,
            )
        }
    }
}

@Composable
private fun PermissionsSection(
    access: SettingsAccess,
    onAccess: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        HlSectionLabel(stringResource(R.string.settings_permissions))
        listOf(
            Triple(R.string.permission_nearby, R.string.permission_nearby_why, access.nearby),
            Triple(R.string.permission_notifications, R.string.permission_notifications_why, access.notifications),
            Triple(R.string.permission_media, R.string.permission_media_why, access.media),
            Triple(R.string.permission_location, R.string.permission_location_why, access.location),
        ).forEachIndexed { index, (title, why, status) ->
            HlValueRow(
                value = stringResource(title),
                detail = stringResource(why),
                hint = stringResource(accessText(status)),
                onClick = if (status == Access.GRANTED) null else ({ onAccess(index) }),
                container = HlTheme.colors.panel,
            )
        }
    }
}

/** The status word shown on a permission row. */
@StringRes
internal fun accessText(status: Access): Int =
    when (status) {
        Access.GRANTED -> R.string.access_granted
        Access.ASK -> R.string.access_ask
        Access.BLOCKED -> R.string.access_blocked
    }

/** Sample state for previews and screenshot tests. */
internal val PreviewSettings =
    SettingsUiState(
        theme = AppTheme.NIGHT,
        bike = SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040"),
        meters = listOf(SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040")),
    )

/** Sample access for previews and screenshot tests: one of each status. */
internal val PreviewAccess = SettingsAccess(Access.GRANTED, Access.ASK, Access.BLOCKED, Access.ASK)

/** Actions that do nothing, for previews and screenshot tests. */
internal val NoActions = SettingsActions({}, {}, {}, {}, {})

@Preview(name = "Settings · Night", widthDp = 411, heightDp = 900)
@Composable
private fun SettingsNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { SettingsScreen(PreviewSettings, PreviewAccess, NoActions) }
    }
}

@Preview(name = "Settings · Bento", widthDp = 411, heightDp = 900)
@Composable
private fun SettingsBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) {
            SettingsScreen(PreviewSettings.copy(theme = AppTheme.BENTO), PreviewAccess, NoActions)
        }
    }
}

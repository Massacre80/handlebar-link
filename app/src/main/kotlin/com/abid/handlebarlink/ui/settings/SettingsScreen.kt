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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIconButton
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlSection
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
    /** Open Appearance › Theme. */
    val onTheme: () -> Unit,
    /** Open Appearance › Mode. */
    val onMode: () -> Unit,
    /** Open the Permissions page. */
    val onPermissions: () -> Unit,
    /** Choose a bike. */
    val onBike: (SelectedBike) -> Unit,
    /** Forget the bike and run setup again (pairs a meter in the app). */
    val onSetup: () -> Unit,
    /** Back. */
    val onBack: () -> Unit,
)

/**
 * Settings (not in the design file; built from its components), in three grouped sections (owner,
 * 2026-11-17): the bike; the permissions, summarised, on their own page; and the appearance, its theme and
 * mode each on their own page.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    access: SettingsAccess,
    actions: SettingsActions,
) {
    SubPage(stringResource(R.string.settings), actions.onBack) {
        BikeSection(state, access.nearby, actions)
        HlSection(stringResource(R.string.settings_permissions)) {
            val all = access.all()
            val allowed = all.count { it == Access.GRANTED }
            HlValueRow(
                value =
                    if (allowed == all.size) {
                        stringResource(R.string.settings_permissions_all)
                    } else {
                        stringResource(R.string.settings_permissions_some, allowed, all.size)
                    },
                detail = stringResource(R.string.settings_permissions_detail),
                hint = null,
                onClick = actions.onPermissions,
                container = Color.Transparent,
                chevron = true,
            )
        }
        HlSection(stringResource(R.string.settings_appearance)) {
            HlValueRow(
                value = stringResource(R.string.settings_theme),
                hint = stringResource(themeName(state.theme)),
                onClick = actions.onTheme,
                container = Color.Transparent,
                chevron = true,
            )
            HlGroupDivider()
            HlValueRow(
                value = stringResource(R.string.settings_mode),
                hint = stringResource(modeName(state.theme)),
                onClick = actions.onMode,
                container = Color.Transparent,
                chevron = true,
            )
        }
    }
}

@Composable
private fun BikeSection(
    state: SettingsUiState,
    nearby: Access,
    actions: SettingsActions,
) {
    HlSection(stringResource(R.string.settings_bike)) {
        val meters = state.meters
        when {
            nearby != Access.GRANTED -> {
                HlValueRow(stringResource(R.string.bike_needs_nearby), hint = null, container = Color.Transparent)
            }

            meters.isNullOrEmpty() -> {
                HlValueRow(
                    stringResource(R.string.bike_none_paired),
                    hint = stringResource(R.string.bike_pair_hint),
                    onClick = actions.onSetup,
                    container = Color.Transparent,
                    chevron = true,
                )
            }

            else -> {
                Column(Modifier.selectableGroup()) {
                    meters.forEachIndexed { index, meter ->
                        if (index > 0) HlGroupDivider()
                        HlOptionRow(
                            icon = HlIcons.Bluetooth,
                            title = meter.name,
                            subtitle = meter.address,
                            selected = meter.address == state.bike?.address,
                            onClick = { actions.onBike(meter) },
                            container = Color.Transparent,
                        )
                    }
                }
                HlGroupDivider()
                HlValueRow(
                    stringResource(R.string.settings_setup_again),
                    hint = null,
                    onClick = actions.onSetup,
                    container = Color.Transparent,
                    chevron = true,
                )
            }
        }
    }
}

/** The four permissions in Settings' order. */
internal fun SettingsAccess.all(): List<Access> = listOf(nearby, notifications, media, location)

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
internal val NoActions = SettingsActions({}, {}, {}, {}, {}, {})

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

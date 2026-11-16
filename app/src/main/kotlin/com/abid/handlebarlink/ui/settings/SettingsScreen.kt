package com.abid.handlebarlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.AppTheme

/** Settings (not in the design file; built from its components): the look for now, the bike in M3.3. */
@Composable
fun SettingsScreen(
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    onBack: () -> Unit,
) {
    val spacing = HlTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        verticalArrangement = Arrangement.spacedBy(spacing.s20),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s8)) {
            HlIconButton(HlIcons.ChevronRight, stringResource(R.string.back), onBack, Modifier.rotate(HALF_TURN))
            HlText(
                stringResource(R.string.settings),
                HlTheme.typography.title,
                HlTheme.colors.ink,
                Modifier.semantics { heading() },
            )
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
            HlSectionLabel(stringResource(R.string.settings_look))
            HlOptionRow(
                icon = null,
                title = stringResource(R.string.theme_night),
                subtitle = stringResource(R.string.theme_night_detail),
                selected = theme == AppTheme.NIGHT,
                onClick = { onTheme(AppTheme.NIGHT) },
            )
            HlOptionRow(
                icon = null,
                title = stringResource(R.string.theme_bento),
                subtitle = stringResource(R.string.theme_bento_detail),
                selected = theme == AppTheme.BENTO,
                onClick = { onTheme(AppTheme.BENTO) },
            )
        }
    }
}

private const val HALF_TURN = 180f

@Preview(name = "Settings · Night", widthDp = 411, heightDp = 600)
@Composable
private fun SettingsNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { SettingsScreen(AppTheme.NIGHT, onTheme = {}, onBack = {}) }
    }
}

@Preview(name = "Settings · Bento", widthDp = 411, heightDp = 600)
@Composable
private fun SettingsBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) { SettingsScreen(AppTheme.BENTO, onTheme = {}, onBack = {}) }
    }
}

package com.abid.handlebarlink.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.data.AppTheme

/** Settings › Appearance: the two looks; the whole app switches as soon as one is chosen. */
@Composable
fun AppearanceScreen(
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    onBack: () -> Unit,
) {
    SubPage(stringResource(R.string.settings_appearance), onBack) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
            THEMES.forEach { (option, detail) ->
                HlOptionRow(
                    icon = null,
                    title = stringResource(themeName(option)),
                    subtitle = stringResource(detail),
                    selected = theme == option,
                    onClick = { onTheme(option) },
                    container = HlTheme.colors.panel,
                )
            }
        }
    }
}

private val THEMES =
    listOf(
        AppTheme.NIGHT to R.string.theme_night_detail,
        AppTheme.BENTO to R.string.theme_bento_detail,
    )

package com.abid.handlebarlink.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.data.AppTheme

/** Settings › Appearance › Theme: Night or Bento; the whole app switches as soon as one is chosen. */
@Composable
fun ThemeScreen(
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    onBack: () -> Unit,
) {
    SubPage(stringResource(R.string.settings_theme), onBack) {
        HlGroup(Modifier.selectableGroup()) {
            THEMES.forEachIndexed { index, (option, detail) ->
                // No hairline beside the chosen (filled) row.
                if (index > 0 && theme != option && theme != THEMES[index - 1].first) HlGroupDivider()
                HlOptionRow(
                    icon = null,
                    title = stringResource(themeName(option)),
                    subtitle = stringResource(detail),
                    selected = theme == option,
                    onClick = { onTheme(option) },
                    container = Color.Transparent,
                )
            }
        }
    }
}

/**
 * Settings › Appearance › Mode: dark or light, separate from the theme (owner, 2026-11-17). Each theme has
 * one palette so far, Night dark and Bento light: the other mode is shown, dimmed, until it is designed.
 */
@Composable
fun ModeScreen(
    theme: AppTheme,
    onBack: () -> Unit,
) {
    SubPage(stringResource(R.string.settings_mode), onBack) {
        val dark = theme == AppTheme.NIGHT
        HlGroup(Modifier.selectableGroup()) {
            // One of the two is always the chosen (filled) row: no hairline between them.
            ModeRow(R.string.mode_dark, R.string.mode_dark_detail, available = dark, theme)
            ModeRow(R.string.mode_light, R.string.mode_light_detail, available = !dark, theme)
        }
    }
}

/** The mode [theme] has; Night is dark and Bento light (one palette each so far). */
@StringRes
internal fun modeName(theme: AppTheme): Int =
    when (theme) {
        AppTheme.NIGHT -> R.string.mode_dark
        AppTheme.BENTO -> R.string.mode_light
    }

/** A mode: the theme's own is chosen; the other is not designed yet for [theme]. */
@Composable
private fun ModeRow(
    @StringRes mode: Int,
    @StringRes detail: Int,
    available: Boolean,
    theme: AppTheme,
) {
    HlOptionRow(
        icon = null,
        title = stringResource(mode),
        subtitle =
            if (available) {
                stringResource(detail)
            } else {
                stringResource(R.string.mode_not_designed, stringResource(themeName(theme)))
            },
        selected = available,
        onClick = {},
        container = Color.Transparent,
        enabled = available,
    )
}

private val THEMES =
    listOf(
        AppTheme.NIGHT to R.string.theme_night_detail,
        AppTheme.BENTO to R.string.theme_bento_detail,
    )

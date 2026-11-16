package com.abid.handlebarlink.ui.later

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/**
 * A tab whose feature is not built yet: its title and what will live there. Empty on purpose; the tab
 * exists so the navigation is the final one (decision 2026-11-17: empty states, never made-up data).
 */
@Composable
fun LaterScreen(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes message: Int,
    contentPadding: PaddingValues,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(contentPadding)
            .padding(horizontal = spacing.s24),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = HlTheme.sizes.contentMax),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s12),
        ) {
            Box(
                Modifier.size(HlTheme.sizes.controlLarge).background(colors.panel, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(icon, colors.ink)
            }
            HlText(
                stringResource(title),
                HlTheme.typography.title,
                colors.ink,
                Modifier.semantics { heading() },
            )
            HlText(stringResource(message), HlTheme.typography.body, colors.inkMuted, textAlign = TextAlign.Center)
        }
    }
}

@Preview(name = "Later · Night", widthDp = 411, heightDp = 600)
@Composable
private fun LaterNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) {
            LaterScreen(HlIcons.Rides, R.string.tab_rides, R.string.later_rides, PaddingValues())
        }
    }
}

@Preview(name = "Later · Bento", widthDp = 411, heightDp = 600)
@Composable
private fun LaterBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) {
            LaterScreen(HlIcons.Garage, R.string.tab_garage, R.string.later_garage, PaddingValues())
        }
    }
}

package com.abid.handlebarlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.AppTheme
import dagger.hilt.android.AndroidEntryPoint

/** The single activity. Screens arrive with the components (docs/PLAN.md, UI milestones). */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge without SystemBarStyle (deprecated in activity 1.14): bar icons follow our theme.
        WindowCompat.enableEdgeToEdge(window)
        super.onCreate(savedInstanceState)
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            AppTheme(theme) { Placeholder() }
        }
    }
}

/** Applies the rider's theme and matches the system bar icons to it (edge-to-edge skill). */
@Composable
private fun AppTheme(
    theme: AppTheme,
    content: @Composable () -> Unit,
) {
    val variant =
        when (theme) {
            AppTheme.NIGHT -> HlThemeVariant.NIGHT
            AppTheme.BENTO -> HlThemeVariant.BENTO
        }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as ComponentActivity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = variant == HlThemeVariant.BENTO
                isAppearanceLightNavigationBars = variant == HlThemeVariant.BENTO
            }
        }
    }
    HlTheme(variant, content)
}

@Composable
private fun Placeholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(HlTheme.colors.screen).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = "CB350RS", style = HlTheme.typography.wordmark.copy(color = HlTheme.colors.ink))
    }
}

@Preview
@Composable
private fun PlaceholderNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) { Placeholder() }
}

@Preview
@Composable
private fun PlaceholderBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) { Placeholder() }
}

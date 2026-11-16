package com.abid.handlebarlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint

/** The single activity: the rider's theme around [AppRoot]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge without SystemBarStyle (deprecated in activity 1.14): bar icons follow our theme.
        WindowCompat.enableEdgeToEdge(window)
        super.onCreate(savedInstanceState)
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            AppTheme(theme) { AppRoot() }
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

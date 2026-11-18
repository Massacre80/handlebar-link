package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * Keeps the status bar's clock and icons readable over content that scrolls behind them: the page colour
 * fading to clear, a little taller than the status bar. Drawn after (over) the scrolling content, as the
 * edge-to-edge skill's "Lists" section describes (`StatusBarProtection`).
 */
@Composable
public fun HlStatusBarProtection(modifier: Modifier = Modifier) {
    val screen = HlTheme.colors.screen
    val height = with(LocalDensity.current) { (WindowInsets.statusBars.getTop(this) * PROTECTION_HEIGHT).toDp() }
    Spacer(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    listOf(screen, screen.copy(alpha = PROTECTION_MID_ALPHA), Color.Transparent),
                ),
            ),
    )
}

/** The skill's proportions: 1.2 status bars tall, 80 % at the middle stop. */
private const val PROTECTION_HEIGHT = 1.2f
private const val PROTECTION_MID_ALPHA = 0.8f

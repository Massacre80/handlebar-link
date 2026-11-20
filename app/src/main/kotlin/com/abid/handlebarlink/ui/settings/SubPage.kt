package com.abid.handlebarlink.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlIconButton
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlStatusBarProtection
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.ui.frame.pageAll
import com.abid.handlebarlink.ui.frame.pageTop
import com.abid.handlebarlink.ui.setup.navigationBarRoom

/**
 * A full page under Settings (Settings itself, Appearance): back and the [title], then [content] in a
 * scrolling column of at most the content width, inside the system bars.
 */
@Composable
internal fun SubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = HlTheme.spacing
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.pageAll)
                .wrapContentWidth()
                .widthIn(max = HlTheme.sizes.contentMax)
                .padding(horizontal = spacing.s16, vertical = spacing.s8),
            // Sections stand apart (24 dp); a label sits 8 dp above its group (HlSection).
            verticalArrangement = Arrangement.spacedBy(spacing.s24),
        ) {
            TitleRow(title, onBack)
            content()
        }
        // The page scrolls behind the status bar (edge to edge); this keeps the clock and icons readable.
        HlStatusBarProtection(Modifier.align(Alignment.TopCenter))
    }
}

/**
 * A full page under Settings on its side, in two panes below the status bar and inside the mirrored sides: back and
 * the [title] above [start], which fills the rest of its pane's height above the navigation bar (Handlebar's switch),
 * and [end] scrolling on its own beside it. The navigation bar's room is inside [end]'s scroll, so its last rows
 * scroll to the screen's edge instead of being cut off above the bar.
 */
@Composable
internal fun SubPagePanes(
    title: String,
    onBack: () -> Unit,
    start: @Composable ColumnScope.() -> Unit,
    end: @Composable ColumnScope.() -> Unit,
) {
    val spacing = HlTheme.spacing
    val bottom = navigationBarRoom()
    Row(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.pageTop)
            .padding(horizontal = spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(spacing.s24),
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(top = spacing.s8, bottom = bottom + spacing.s8),
            verticalArrangement = Arrangement.spacedBy(spacing.s16),
        ) {
            TitleRow(title, onBack)
            start()
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = spacing.s8, bottom = bottom + spacing.s8),
            verticalArrangement = Arrangement.spacedBy(spacing.s24),
            content = end,
        )
    }
}

/** Back and the page's title. */
@Composable
private fun TitleRow(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8),
    ) {
        HlIconButton(HlIcons.ChevronRight, stringResource(R.string.back), onBack, Modifier.rotate(HALF_TURN))
        HlText(title, HlTheme.typography.title, HlTheme.colors.ink, Modifier.semantics { heading() })
    }
}

/** A theme's name, as listed on Appearance and shown on Settings' row. */
@StringRes
internal fun themeName(theme: AppTheme): Int =
    when (theme) {
        AppTheme.NIGHT -> R.string.theme_night
        AppTheme.BENTO -> R.string.theme_bento
    }

private const val HALF_TURN = 180f

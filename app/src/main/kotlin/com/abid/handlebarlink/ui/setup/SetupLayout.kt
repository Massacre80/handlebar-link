package com.abid.handlebarlink.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.frame.pageAll

/**
 * A setup page in two parts: [start] (the bike, the ring or the step's heading) and [end] (the step's words, list and
 * actions). Upright they stack in one column ([SetupColumn], the artboards); on its side ([WindowLayout.PANES]) they
 * stand side by side ([SetupPanes]), so the bike and the Start button are both on screen at 411 dp high.
 */
@Composable
internal fun SetupPage(
    start: @Composable ColumnScope.() -> Unit,
    end: @Composable ColumnScope.() -> Unit,
) {
    if (LocalWindowLayout.current == WindowLayout.PANES) {
        SetupPanes(start, end)
    } else {
        SetupColumn {
            start()
            end()
        }
    }
}

/** True on its side, where [SetupPage] shows two panes. */
@Composable
internal fun setupPanes(): Boolean = LocalWindowLayout.current == WindowLayout.PANES

/**
 * Setup on its side: two equal panes inside the system bars and the mirrored sides, each at least the screen's height
 * (weighted spacers centre or push their content) and scrolling on its own when the text does not fit.
 */
@Composable
private fun SetupPanes(
    start: @Composable ColumnScope.() -> Unit,
    end: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.pageAll),
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s16),
    ) {
        listOf(start, end).forEach { pane ->
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(bottom = HlTheme.spacing.s16),
                    content = pane,
                )
            }
        }
    }
}

/**
 * A setup page upright: the full screen inside the system bars, at most the content width, scrolling when the text
 * or the screen does not fit (docs/LESSONS.md 22). Its height is at least the screen's, so a weighted
 * spacer pushes the actions to the bottom like the artboards.
 */
@Composable
private fun SetupColumn(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .windowInsetsPadding(WindowInsets.pageAll)
                .wrapContentWidth()
                .widthIn(max = HlTheme.sizes.contentMax)
                .padding(bottom = HlTheme.spacing.s24),
            content = content,
        )
    }
}

/** Where a step's list starts: well below the heading upright, level with the top bar beside it on its side. */
@Composable
internal fun setupListTop() = if (setupPanes()) HlTheme.spacing.s16 else HlTheme.spacing.s16 * 2

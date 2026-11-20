package com.abid.handlebarlink.ui.frame

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Padding that reads [state] every time it is asked, so a page holds one object and always lays out with the current
 * value. The tab pages' room for the bar or rail depends on the window's insets, which arrive after the first frame
 * and change when the phone turns; Navigation 3 keeps each page's entry content from when the page was first shown,
 * so a plain [PaddingValues] captured there stayed at its first value (seen on emulator-5554, 2026-11-20: the CarPlay
 * and Devices tabs kept the rail's room without the cutout side, 48 dp too close to the rail, while Home had it).
 */
@Stable
class LivePadding(
    private val state: State<PaddingValues>,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        state.value.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = state.value.calculateTopPadding()

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        state.value.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = state.value.calculateBottomPadding()

    override fun toString(): String = "LivePadding(${state.value})"
}

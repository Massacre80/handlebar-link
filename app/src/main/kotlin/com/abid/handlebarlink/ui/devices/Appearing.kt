package com.abid.handlebarlink.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import com.abid.handlebarlink.core.designsystem.theme.HlMotion
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/** Grows open and fades in (a row found by the search, the other devices unfolding). */
internal fun unfold(motion: HlMotion): EnterTransition =
    expandVertically(motion.layout(IntSize.VisibilityThreshold)) + fadeIn(motion.fade())

/** Folds away and fades out. */
internal fun fold(motion: HlMotion): ExitTransition =
    shrinkVertically(motion.layout(IntSize.VisibilityThreshold)) + fadeOut(motion.fade())

/** [content] unfolds when it first appears (a device the search just found), instead of popping in. */
@Composable
internal fun Appearing(content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, enter = unfold(HlTheme.motion)) { content() }
}

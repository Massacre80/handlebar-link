package com.abid.handlebarlink.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import com.abid.handlebarlink.core.designsystem.theme.HlMotion
import kotlin.math.roundToInt

/**
 * The page transition for [move] (Navigation 3 "Animate between destinations"; motion tokens from the
 * design system). Tabs slide by their order and fade (the new page moves a fifth of the width); an opened
 * page slides in over the whole width from the right and back out the same way. Springs, like Motion.
 */
fun pageTransition(
    move: Move,
    motion: HlMotion,
): ContentTransform {
    val slide = motion.layout(IntOffset.VisibilityThreshold)
    val fadeIn = fadeIn(motion.fade())
    val fadeOut = fadeOut(motion.fade())

    fun shift(width: Int) = (width * motion.tabShift).roundToInt()
    return when (move) {
        Move.TAB_FORWARD -> {
            slideInHorizontally(slide) { shift(it) } + fadeIn togetherWith
                slideOutHorizontally(slide) { -shift(it) } + fadeOut
        }

        Move.TAB_BACKWARD -> {
            slideInHorizontally(slide) { -shift(it) } + fadeIn togetherWith
                slideOutHorizontally(slide) { shift(it) } + fadeOut
        }

        Move.PUSH -> {
            slideInHorizontally(slide) { it } + fadeIn togetherWith
                slideOutHorizontally(slide) { -shift(it) } + fadeOut
        }

        Move.POP -> {
            slideInHorizontally(slide) { -shift(it) } + fadeIn togetherWith
                slideOutHorizontally(slide) { it } + fadeOut
        }
    }
}

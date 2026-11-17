package com.abid.handlebarlink.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.abid.handlebarlink.core.designsystem.theme.HlMotion
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/** The pages' shared-transition scope ("Shared elements with Navigation Compose": NavDisplay inside it). */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * A container transform: what is tapped (a tile, a row) and the page it opens share a key, and the one grows
 * into the other, Back shrinking it again. sharedBounds with RemeasureToBounds, the page laid out at every
 * size in between, clipped to the card's corners; the app's layout spring moves the bounds.
 */
class ContainerTransform internal constructor(
    internal val shared: SharedTransitionScope,
    internal val state: SharedTransitionScope.SharedContentState,
    internal val pages: AnimatedVisibilityScope,
    internal val bounds: BoundsTransform,
    internal val shape: Shape,
)

/** The transform for [key]; null outside NavDisplay (previews, screenshots), where nothing moves. */
@Composable
fun rememberContainerTransform(key: String): ContainerTransform? {
    val shared = LocalSharedTransitionScope.current ?: return null
    val pages = LocalNavAnimatedContentScope.current
    val motion = HlTheme.motion
    val state = shared.rememberSharedContentState(key)
    val shape = RoundedCornerShape(HlTheme.radii.card)
    return remember(shared, state, pages, motion, shape) {
        ContainerTransform(
            shared,
            state,
            pages,
            BoundsTransform {
                _,
                _,
                ->
                motion.transform(Rect.VisibilityThreshold)
            },
            shape,
        )
    }
}

/**
 * The content inside a [containerTransform]: laid out once at its final size and revealed as the bounds
 * grow, instead of reflowing at every size in between ("Customize shared element transition",
 * skipToLookaheadSize).
 */
fun Modifier.containerContent(transform: ContainerTransform?): Modifier =
    if (transform == null) this else with(transform.shared) { this@containerContent.skipToLookaheadSize() }

/** Takes part in [transform] (nothing when it is null). */
fun Modifier.containerTransform(transform: ContainerTransform?): Modifier =
    if (transform == null) {
        this
    } else {
        with(transform.shared) {
            this@containerTransform.sharedBounds(
                transform.state,
                transform.pages,
                boundsTransform = transform.bounds,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(transform.shape),
            )
        }
    }

/**
 * A page opened by a container transform (NavDisplay scene metadata): no slide or fade of its own, and the
 * page underneath stays, fully shown, until the transform has settled, so the card grows over it and shrinks
 * back onto it; both ways and on the back gesture. (ExitTransition.KeepUntilTransitionsFinished, the docs'
 * way, is internal in this Compose release: a snap after the hold does the same.)
 */
fun containerPage(motion: HlMotion): Map<String, Any> {
    val hold: () -> ContentTransform = {
        EnterTransition.None togetherWith fadeOut(snap(delayMillis = motion.transformHoldMillis))
    }
    return NavDisplay.transitionSpec { hold() } +
        NavDisplay.popTransitionSpec { hold() } +
        NavDisplay.predictivePopTransitionSpec { _ -> hold() }
}

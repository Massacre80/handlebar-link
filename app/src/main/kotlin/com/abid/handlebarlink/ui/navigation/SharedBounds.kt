package com.abid.handlebarlink.ui.navigation

import android.view.RoundedCorner
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.theme.HlMotion
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/** The pages' shared-transition scope ("Shared elements with Navigation Compose": NavDisplay inside it). */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * A container transform: what is tapped (a tile, a row) and the page it opens share a key, and the one grows
 * into the other, Back shrinking it again. One critically damped spring moves the bounds
 * ([HlMotion.transform]); the two contents cross-fade quickly at the start, overlapping so the card stays
 * solid, and the page, laid out at its final size, is revealed as the card grows; the corners go from the
 * card's radius to the display's own, so nothing jumps when the clip ends.
 */
class ContainerTransform internal constructor(
    internal val shared: SharedTransitionScope,
    internal val state: SharedTransitionScope.SharedContentState,
    internal val pages: AnimatedVisibilityScope,
    internal val bounds: BoundsTransform,
    internal val shape: Shape,
    internal val motion: HlMotion,
)

/**
 * The transform for [key]; [expanded] for the page side, false for what is tapped. Null outside NavDisplay
 * (previews, screenshots), where nothing moves.
 */
@Composable
fun rememberContainerTransform(
    key: String,
    expanded: Boolean,
): ContainerTransform? {
    val shared = LocalSharedTransitionScope.current ?: return null
    val pages = LocalNavAnimatedContentScope.current
    val motion = HlTheme.motion
    val state = shared.rememberSharedContentState(key)
    val card = HlTheme.radii.card
    val screen = rememberScreenCornerRadius()
    // Shown, the page has the display's corners and the tile the card's; each goes to the other's while hidden.
    val radius =
        pages.transition.animateDp({ motion.transform(Dp.VisibilityThreshold) }, label = "corners") { now ->
            if ((now == EnterExitState.Visible) == expanded) screen else card
        }
    return remember(shared, state, pages, motion, radius) {
        ContainerTransform(
            shared,
            state,
            pages,
            BoundsTransform { _, _ -> motion.transform(Rect.VisibilityThreshold) },
            CornerShape(radius),
            motion,
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
        val motion = transform.motion
        with(transform.shared) {
            this@containerTransform.sharedBounds(
                transform.state,
                transform.pages,
                enter = fadeIn(tween(motion.transformFadeInMillis)),
                exit = fadeOut(tween(motion.transformFadeOutMillis)),
                boundsTransform = transform.bounds,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(transform.shape),
            )
        }
    }

/**
 * The dim between the receding page and the card growing over it: fades in as the page opens and out as it
 * closes, on the transform's spring; under the card in [ContainerPage]. Nothing outside NavDisplay.
 */
@Composable
fun ContainerScrim() {
    if (LocalSharedTransitionScope.current == null) return
    val pages = LocalNavAnimatedContentScope.current
    val motion = HlTheme.motion
    val scrim = HlTheme.colors.scrim
    with(pages) {
        Spacer(
            Modifier
                .fillMaxSize()
                .animateEnterExit(enter = fadeIn(motion.transform()), exit = fadeOut(motion.transform()))
                .drawBehind { drawRect(scrim) },
        )
    }
}

/**
 * A page that grows out of what was tapped ([key]): the scrim over the page underneath, then the page as a
 * card in the transform, its [content] at its final size. Its entry takes [containerPage] metadata.
 */
@Composable
fun ContainerPage(
    key: String,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ContainerScrim()
        val transform = rememberContainerTransform(key, expanded = true)
        HlBackground(Modifier.fillMaxSize().containerTransform(transform)) {
            Box(Modifier.containerContent(transform)) { content() }
        }
    }
}

/**
 * A page opened by a container transform (NavDisplay scene metadata). Opening, the page underneath recedes
 * (scales back on the transform's spring, under [ContainerScrim]) and stays, fully drawn, until the transform
 * has settled; closing, it comes forward again while the card shrinks onto its tile. Both ways and on the
 * back gesture. (ExitTransition.KeepUntilTransitionsFinished, the docs' way to keep a page, is internal in
 * this Compose release: a snap after the hold does the same.)
 */
fun containerPage(motion: HlMotion): Map<String, Any> {
    val hold = fadeOut(snap(delayMillis = motion.transformHoldMillis))
    val open: () -> ContentTransform = {
        EnterTransition.None togetherWith scaleOut(motion.transform(), motion.recedeScale) + hold
    }
    val close: () -> ContentTransform = {
        scaleIn(motion.transform(), motion.recedeScale) togetherWith hold
    }
    return NavDisplay.transitionSpec { open() } +
        NavDisplay.popTransitionSpec { close() } +
        NavDisplay.predictivePopTransitionSpec { _ -> close() }
}

/** The display's corner radius (Android 12's RoundedCorner), 0 on square screens. */
@Composable
private fun rememberScreenCornerRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        val corner = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        with(density) { (corner?.radius ?: 0).toDp() }
    }
}

/** Rounded corners whose radius is read while drawing, so a changing radius never recomposes. */
private class CornerShape(
    private val radius: State<Dp>,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rounded(RoundRect(size.toRect(), CornerRadius(with(density) { radius.value.toPx() })))
}

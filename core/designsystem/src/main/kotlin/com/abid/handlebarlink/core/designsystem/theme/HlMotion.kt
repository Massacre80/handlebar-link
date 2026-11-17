package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/**
 * Motion tokens (`--motion-*` in design/bento.css): springs for anything that moves, like Motion on the
 * web, and short fades. Compose follows the system "Remove animations" setting by itself.
 */
@Immutable
public data class HlMotion(
    /** Spring damping: settles without a visible bounce. */
    val damping: Float = 0.85f,
    /** Spring stiffness for layout moves (pages, the tab chip). */
    val stiffness: Float = 400f,
    /** Spring stiffness for press feedback. */
    val pressStiffness: Float = 1500f,
    /** Scale of a pressed card or button. */
    val pressScale: Float = 0.97f,
    /** How far tab pages move while they fade, as a fraction of the width. */
    val tabShift: Float = 0.2f,
    /** Fades. */
    val fadeMillis: Int = 180,
    /** Damping of a container transform (a card growing into a page): none of the layout spring's overshoot. */
    val transformDamping: Float = 1f,
    /** How long the page under a container transform stays: the transform spring settles in about 350 ms. */
    val transformHoldMillis: Int = 400,
    /** How far the page under a growing card recedes (iOS's zoom transition scales it back slightly). */
    val recedeScale: Float = 0.94f,
    /** The tapped tile's content fading out as it starts to grow. */
    val transformFadeOutMillis: Int = 90,
    /** The page's content fading in over the growing card, overlapping the fade out so the card stays solid. */
    val transformFadeInMillis: Int = 150,
    /** One way of the "looking" pulse. */
    val pulseMillis: Int = 1100,
    /** The pulse's lowest alpha. */
    val pulseAlpha: Float = 0.35f,
) {
    /** The spring for layout moves; [threshold] is the type's visibility threshold when it has one. */
    public fun <T> layout(threshold: T? = null): SpringSpec<T> = spring(damping, stiffness, threshold)

    /** The spring for a container transform: critically damped, no bounce at either end. */
    public fun <T> transform(threshold: T? = null): SpringSpec<T> = spring(transformDamping, stiffness, threshold)

    /** The spring for press feedback. */
    public fun <T> press(): SpringSpec<T> = spring(damping, pressStiffness)

    /** A fade. */
    public fun <T> fade(): TweenSpec<T> = tween(fadeMillis)
}

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
    /** One way of the "looking" pulse. */
    val pulseMillis: Int = 1100,
    /** The pulse's lowest alpha. */
    val pulseAlpha: Float = 0.35f,
) {
    /** The spring for layout moves; [threshold] is the type's visibility threshold when it has one. */
    public fun <T> layout(threshold: T? = null): SpringSpec<T> = spring(damping, stiffness, threshold)

    /** The spring for press feedback. */
    public fun <T> press(): SpringSpec<T> = spring(damping, pressStiffness)

    /** A fade. */
    public fun <T> fade(): TweenSpec<T> = tween(fadeMillis)
}

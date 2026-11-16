package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.runtime.Immutable

/**
 * One blurred ellipse of a ground shadow, in the units of the image box it sits under ([HlGroundShadow]):
 * left, top, width and height, the CSS blur radius and the ink alpha.
 */
@Immutable
public data class ShadowEllipse(
    /** Left edge. */
    val x: Float,
    /** Top edge. */
    val y: Float,
    /** Width. */
    val width: Float,
    /** Height. */
    val height: Float,
    /** CSS `filter: blur(…)` radius. */
    val blur: Float,
    /** Alpha of the ink colour. */
    val alpha: Float,
)

/** A ground shadow drawn under an image: [ellipses] in a [boxWidth] × [boxHeight] coordinate box. */
@Immutable
public data class HlGroundShadow(
    /** Width of the image box the ellipses are measured in. */
    val boxWidth: Float,
    /** Height of that box. */
    val boxHeight: Float,
    /** The ellipses, back to front. */
    val ellipses: List<ShadowEllipse>,
)

/**
 * The CB350 render's ground shadow from the design file ("Home 2 — Minimal", drafts page): a wide soft
 * ambient shadow and tighter, darker contact shadows under each tyre, measured against the render's
 * 500 × 365 box (positions there minus the box's -6, 330 origin).
 */
public val BikeGroundShadow: HlGroundShadow =
    HlGroundShadow(
        boxWidth = 500f,
        boxHeight = 365f,
        ellipses =
            listOf(
                ShadowEllipse(x = 6f, y = 322f, width = 470f, height = 44f, blur = 18f, alpha = 0.10f),
                ShadowEllipse(x = 26f, y = 327f, width = 136f, height = 20f, blur = 8f, alpha = 0.22f),
                ShadowEllipse(x = 54f, y = 333f, width = 80f, height = 8f, blur = 3f, alpha = 0.55f),
                ShadowEllipse(x = 306f, y = 350f, width = 170f, height = 26f, blur = 10f, alpha = 0.24f),
                ShadowEllipse(x = 346f, y = 356f, width = 96f, height = 10f, blur = 3f, alpha = 0.62f),
                ShadowEllipse(x = 366f, y = 359f, width = 56f, height = 5f, blur = 1.5f, alpha = 0.75f),
                ShadowEllipse(x = 256f, y = 360f, width = 150f, height = 14f, blur = 7f, alpha = 0.12f),
            ),
    )

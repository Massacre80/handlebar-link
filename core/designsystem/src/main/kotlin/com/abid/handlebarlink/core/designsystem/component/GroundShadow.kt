package com.abid.handlebarlink.core.designsystem.component

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import com.abid.handlebarlink.core.designsystem.theme.HlGroundShadow
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws [shadow] behind this element (an image of the same box proportions), scaled to its size, in
 * [color]: each ellipse blurred like its CSS `filter: blur()` (Skia's blur radius ≈ 1.73 × the CSS radius).
 *
 * The blurred ellipses are drawn once into a bitmap per size and colour (`drawWithCache`), and every
 * frame draws that bitmap: blurring them on each frame made page slides stutter (Compose performance
 * skill, "Cache drawing allocations").
 */
public fun Modifier.groundShadow(
    shadow: HlGroundShadow,
    color: Color,
): Modifier =
    drawWithCache {
        val scale = size.width / shadow.boxWidth
        // The blurred ellipses reach about three standard deviations past their edges.
        val reach = shadow.ellipses.map { it.blur * scale * BLUR_REACH }
        val left = floor(shadow.ellipses.indices.minOfOrNull { shadow.ellipses[it].x * scale - reach[it] } ?: 0f)
        val top = floor(shadow.ellipses.indices.minOfOrNull { shadow.ellipses[it].y * scale - reach[it] } ?: 0f)
        val right =
            ceil(
                shadow.ellipses.indices.maxOfOrNull {
                    (shadow.ellipses[it].x + shadow.ellipses[it].width) * scale + reach[it]
                } ?: 0f,
            )
        val bottom =
            ceil(
                shadow.ellipses.indices.maxOfOrNull {
                    (shadow.ellipses[it].y + shadow.ellipses[it].height) * scale + reach[it]
                } ?: 0f,
            )
        val width = (right - left).toInt()
        val height = (bottom - top).toInt()
        if (width <= 0 || height <= 0) return@drawWithCache onDrawBehind {}
        val image = ImageBitmap(width, height)
        val canvas = Canvas(image.asAndroidBitmap())
        canvas.translate(-left, -top)
        shadow.ellipses.forEach { e ->
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.copy(alpha = color.alpha * e.alpha).toArgb()
                    maskFilter = BlurMaskFilter(e.blur * scale * CSS_TO_SKIA_BLUR, BlurMaskFilter.Blur.NORMAL)
                }
            canvas.drawOval(e.x * scale, e.y * scale, (e.x + e.width) * scale, (e.y + e.height) * scale, paint)
        }
        image.prepareToDraw()
        onDrawBehind { drawImage(image, Offset(left, top)) }
    }

/** CSS blur radius (a Gaussian sigma) to Skia's blur radius: radius = (sigma - 0.5) / 0.57735 ≈ 1.73 sigma. */
private const val CSS_TO_SKIA_BLUR = 1.732f

/** A Gaussian is invisible beyond about three standard deviations. */
private const val BLUR_REACH = 3f

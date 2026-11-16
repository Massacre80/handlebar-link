package com.abid.handlebarlink.core.designsystem.component

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.abid.handlebarlink.core.designsystem.theme.HlGroundShadow

/**
 * Draws [shadow] behind this element (an image of the same box proportions), scaled to its size, in
 * [color]: each ellipse blurred like its CSS `filter: blur()` (Skia's blur radius ≈ 1.73 × the CSS radius).
 */
public fun Modifier.groundShadow(
    shadow: HlGroundShadow,
    color: Color,
): Modifier =
    drawBehind {
        val scale = size.width / shadow.boxWidth
        drawIntoCanvas { canvas ->
            shadow.ellipses.forEach { e ->
                val paint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color.copy(alpha = color.alpha * e.alpha).toArgb()
                        maskFilter = BlurMaskFilter(e.blur * scale * CSS_TO_SKIA_BLUR, BlurMaskFilter.Blur.NORMAL)
                    }
                canvas.nativeCanvas.drawOval(
                    e.x * scale,
                    e.y * scale,
                    (e.x + e.width) * scale,
                    (e.y + e.height) * scale,
                    paint,
                )
            }
        }
    }

/** CSS blur radius (a Gaussian sigma) to Skia's blur radius: radius = (sigma - 0.5) / 0.57735 ≈ 1.73 sigma. */
private const val CSS_TO_SKIA_BLUR = 1.732f

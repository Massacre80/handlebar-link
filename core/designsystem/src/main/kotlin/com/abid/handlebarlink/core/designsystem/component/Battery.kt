package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * A battery level, iOS style (owner, 2026-11-18): one rounded body, the charge filling it from the left,
 * the percentage inside, knocked out of the fill so the ground shows through; the terminal on the right.
 * The fill is clipped to the body's own shape, so its corners are concentric with the body's. The fill is
 * the danger colour at [LOW_PERCENT] or less. The number keeps a fixed size so "100" always fits (like the
 * status bar); TalkBack reads [label].
 */
@Composable
public fun HlBattery(
    percent: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val sizes = HlTheme.sizes
    val level = percent.coerceIn(0, FULL)
    val track = colors.ink.copy(alpha = TRACK_ALPHA)
    val fill = if (level <= LOW_PERCENT) colors.danger else colors.ink
    val measurer = rememberTextMeasurer()
    val number = level.toString()
    Spacer(
        modifier
            .clearAndSetSemantics { contentDescription = label }
            .size(sizes.batteryWidth, sizes.batteryHeight)
            // Offscreen: the number clears the pixels under it, letting the ground show through.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val nubWidth = sizes.batteryNub.toPx()
                val gap = sizes.batteryNubGap.toPx()
                val body = Size(size.width - nubWidth - gap, size.height)
                val radius = CornerRadius(body.height * BODY_ROUNDING)
                val outline = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, body), radius)) }
                val nubHeight = body.height * NUB_SHARE
                val nubAt = Offset(body.width + gap, (body.height - nubHeight) / 2)
                // The number at a fixed pixel size: the glyph does not grow with the font scale.
                val text =
                    measurer.measure(
                        number,
                        TextStyle(
                            fontSize = (body.height * NUMBER_SHARE / density).sp / fontScale,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            letterSpacing = TIGHT.sp,
                        ),
                    )
                val textAt =
                    Offset((body.width - text.size.width) / 2f, (body.height - text.size.height) / 2f)
                onDrawBehind {
                    drawPath(outline, track)
                    clipPath(outline) { drawRect(fill, size = Size(body.width * level / FULL, body.height)) }
                    drawRoundRect(track, nubAt, Size(nubWidth, nubHeight), CornerRadius(nubWidth))
                    drawText(text, Color.Black, textAt, blendMode = BlendMode.Clear)
                }
            },
    )
}

/** At or below this the fill is the danger colour. */
public const val LOW_PERCENT: Int = 20

private const val FULL = 100

/** The empty part of the body. */
private const val TRACK_ALPHA = 0.32f

/** Corner radius as a share of the body's height (iOS's battery: about a third). */
private const val BODY_ROUNDING = 0.32f

/** The terminal's height as a share of the body's. */
private const val NUB_SHARE = 0.38f

/** The number's size as a share of the body's height. */
private const val NUMBER_SHARE = 0.78f

/** Slightly tight figures, so three digits sit well inside. */
private const val TIGHT = -0.3f

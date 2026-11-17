package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * A battery level as Paper "Home 3" draws it on the device tile: the glyph (an outline in half-strength
 * ink, a fill as long as the charge, the terminal) and the percentage in muted ink, 4 dp apart. The fill
 * turns to the danger colour at [LOW_PERCENT] or less. [label] is what the percentage reads ("72%").
 */
@Composable
public fun HlBattery(
    percent: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val level = percent.coerceIn(0, FULL) / FULL.toFloat()
    val outline = colors.ink.copy(alpha = OUTLINE_ALPHA)
    val fill = if (percent <= LOW_PERCENT) colors.danger else colors.ink
    Row(
        modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s4),
    ) {
        Spacer(
            Modifier
                .size(HlTheme.sizes.batteryWidth, HlTheme.sizes.batteryHeight)
                .drawWithCache {
                    // The artboard's 24 × 12 drawing, scaled to the glyph's size.
                    val unit = size.width / VIEW_WIDTH
                    val stroke = Stroke(STROKE * unit)
                    val body = Size(BODY_WIDTH * unit, BODY_HEIGHT * unit)
                    val bodyAt = Offset(INSET * unit, INSET * unit)
                    val fillAt = Offset(FILL_AT * unit, FILL_AT * unit)
                    val fillSize = Size(FILL_MAX * level * unit, FILL_HEIGHT * unit)
                    val nubAt = Offset(NUB_X * unit, NUB_Y * unit)
                    val nub = Size(NUB_WIDTH * unit, NUB_HEIGHT * unit)
                    onDrawBehind {
                        drawRoundRect(outline, bodyAt, body, CornerRadius(BODY_RADIUS * unit), style = stroke)
                        drawRoundRect(fill, fillAt, fillSize, CornerRadius(FILL_RADIUS * unit))
                        drawRoundRect(outline, nubAt, nub, CornerRadius(NUB_RADIUS * unit))
                    }
                },
        )
        HlText(label, HlTheme.typography.meta, colors.inkMuted, maxLines = 1)
    }
}

/** A battery to show: [percent] and how it reads ([label], "72%"). */
public data class HlBatteryLevel(
    val percent: Int,
    val label: String,
)

/** At or below this the fill is the danger colour. */
public const val LOW_PERCENT: Int = 20

private const val FULL = 100
private const val OUTLINE_ALPHA = 0.5f

// Paper "Home 3": viewBox 0 0 24 12.
private const val VIEW_WIDTH = 24f
private const val STROKE = 1.5f
private const val INSET = 0.75f
private const val BODY_WIDTH = 20f
private const val BODY_HEIGHT = 10.5f
private const val BODY_RADIUS = 2.5f
private const val FILL_AT = 2.5f
private const val FILL_MAX = 16.5f
private const val FILL_HEIGHT = 7f
private const val FILL_RADIUS = 1.2f
private const val NUB_X = 22f
private const val NUB_Y = 4f
private const val NUB_WIDTH = 1.6f
private const val NUB_HEIGHT = 4f
private const val NUB_RADIUS = 0.8f

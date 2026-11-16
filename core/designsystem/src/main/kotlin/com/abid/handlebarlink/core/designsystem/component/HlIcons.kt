package com.abid.handlebarlink.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.abid.handlebarlink.core.designsystem.R
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/** The design's icons (`res/drawable/hl_ic_*`), drawn black and tinted by [HlIcon]. */
public object HlIcons {
    /** Home tab. */
    @DrawableRes public val Home: Int = R.drawable.hl_ic_home

    /** Rides tab. */
    @DrawableRes public val Rides: Int = R.drawable.hl_ic_rides

    /** CarPlay tab and card. */
    @DrawableRes public val CarPlay: Int = R.drawable.hl_ic_carplay

    /** Garage tab. */
    @DrawableRes public val Garage: Int = R.drawable.hl_ic_garage

    /** Intercom tab and device. */
    @DrawableRes public val Intercom: Int = R.drawable.hl_ic_intercom

    /** Settings. */
    @DrawableRes public val Settings: Int = R.drawable.hl_ic_settings

    /** Add. */
    @DrawableRes public val Plus: Int = R.drawable.hl_ic_plus

    /** Forward. */
    @DrawableRes public val ChevronRight: Int = R.drawable.hl_ic_chevron_right

    /** Selected. */
    @DrawableRes public val Check: Int = R.drawable.hl_ic_check

    /** A Bluetooth device. */
    @DrawableRes public val Bluetooth: Int = R.drawable.hl_ic_bluetooth

    /** Headset or earbuds. */
    @DrawableRes public val Earbuds: Int = R.drawable.hl_ic_earbuds

    /** Signal: the two outer arcs. */
    @DrawableRes public val SignalOuter: Int = R.drawable.hl_ic_signal_outer

    /** Signal: the inner arc and the dot. */
    @DrawableRes public val SignalInner: Int = R.drawable.hl_ic_signal_inner

    /** Signal lost. */
    @DrawableRes public val SignalOff: Int = R.drawable.hl_ic_signal_off
}

/** Text in a design style and colour; long text ends with an ellipsis. */
@Composable
public fun HlText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(color = color, textAlign = textAlign),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

/**
 * A design icon whose tint changes over time: [tint] is read while drawing (an animated colour never
 * recomposes; Compose performance skill, "phase deferral").
 */
@Composable
public fun HlIcon(
    @DrawableRes icon: Int,
    tint: () -> Color,
    modifier: Modifier = Modifier,
    size: Dp = HlTheme.sizes.icon,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier =
            modifier
                .size(size)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(tint(), blendMode = BlendMode.SrcIn)
                },
    )
}

/** Text whose colour changes over time: [color] is read while drawing. */
@Composable
public fun HlText(
    text: String,
    style: TextStyle,
    color: () -> Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        color = { color() },
    )
}

/** A design icon tinted with [tint]. Decorative unless [contentDescription] is given. */
@Composable
public fun HlIcon(
    @DrawableRes icon: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = HlTheme.sizes.icon,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** A status dot; [glow] adds the soft halo Night uses on "on" dots. */
@Composable
public fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = HlTheme.sizes.dot,
    glow: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .drawBehind {
                if (glow) {
                    drawCircle(
                        Brush.radialGradient(listOf(color.copy(alpha = GLOW_ALPHA), Color.Transparent)),
                        radius = this.size.minDimension * GLOW_SCALE,
                    )
                }
            }.background(color, CircleShape),
    )
}

/**
 * The screen background: Night's green glow rising from the bottom (CSS `radial-gradient(ellipse 120% 55%
 * at 50% 100%, glowInner 0%, glowMid 45%, ground 80%)`), or Bento's flat colour.
 */
@Composable
public fun HlBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = HlTheme.colors
    val glow = HlTheme.variant == HlThemeVariant.NIGHT
    Box(
        modifier.drawBehind {
            drawRect(colors.screen)
            if (glow) {
                val radiusX = size.width * GLOW_WIDTH
                val radiusY = size.height * GLOW_HEIGHT
                val centre = Offset(size.width / 2, size.height)
                scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = centre) {
                    drawCircle(
                        Brush.radialGradient(
                            GLOW_STOP_INNER to colors.glowInner,
                            GLOW_STOP_MID to colors.glowMid,
                            GLOW_STOP_OUTER to colors.screen,
                            center = centre,
                            radius = radiusX,
                        ),
                        radius = radiusX,
                        center = centre,
                    )
                }
            }
        },
        content = content,
    )
}

/** A dashed outline (an empty slot), like CSS `border: dashed`. */
public fun Modifier.dashedOutline(
    color: Color,
    width: Dp,
    radius: Dp,
    dashOn: Dp,
    dashOff: Dp,
): Modifier =
    drawBehind {
        val stroke = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size =
                androidx.compose.ui.geometry
                    .Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius.toPx()),
            style =
                Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn.toPx(), dashOff.toPx())),
                ),
        )
    }

private const val GLOW_ALPHA = 0.6f
private const val GLOW_SCALE = 1.5f
private const val GLOW_WIDTH = 1.2f
private const val GLOW_HEIGHT = 0.55f
private const val GLOW_STOP_INNER = 0f
private const val GLOW_STOP_MID = 0.45f
private const val GLOW_STOP_OUTER = 0.8f

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
import androidx.compose.ui.draw.drawWithCache
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

    /** Removing something (destructive actions). */
    @DrawableRes public val Trash: Int = R.drawable.hl_ic_trash

    /** This phone (as an audio choice). */
    @DrawableRes public val Phone: Int = R.drawable.hl_ic_phone

    /** A speaker with sound: the CB350 switch's up key. */
    @DrawableRes public val Speaker: Int = R.drawable.hl_ic_speaker

    /** A phone handset with signal: the CB350 switch's left key. */
    @DrawableRes public val Call: Int = R.drawable.hl_ic_call

    /** Play or pause. */
    @DrawableRes public val PlayPause: Int = R.drawable.hl_ic_play_pause

    /** Next track. */
    @DrawableRes public val Next: Int = R.drawable.hl_ic_next

    /** Previous track. */
    @DrawableRes public val Previous: Int = R.drawable.hl_ic_previous

    /** Volume up: a speaker with a plus. */
    @DrawableRes public val VolumeUp: Int = R.drawable.hl_ic_volume_up

    /** Volume down: a speaker with a minus. */
    @DrawableRes public val VolumeDown: Int = R.drawable.hl_ic_volume_down

    /** Mute: a speaker crossed out. */
    @DrawableRes public val Mute: Int = R.drawable.hl_ic_mute

    /** Brightness up: a sun with long rays. */
    @DrawableRes public val BrightnessUp: Int = R.drawable.hl_ic_brightness_up

    /** Brightness down: a sun with short rays. */
    @DrawableRes public val BrightnessDown: Int = R.drawable.hl_ic_brightness_down

    /** Switch screen: two arrows passing each other. */
    @DrawableRes public val SwitchScreen: Int = R.drawable.hl_ic_switch_screen

    /** A clock (announce time). */
    @DrawableRes public val Time: Int = R.drawable.hl_ic_time

    /** A battery (announce battery). */
    @DrawableRes public val Battery: Int = R.drawable.hl_ic_battery

    /** Does nothing. */
    @DrawableRes public val None: Int = R.drawable.hl_ic_none

    /** A voice assistant (a microphone). */
    @DrawableRes public val Voice: Int = R.drawable.hl_ic_voice

    /** A Bluetooth device. */
    @DrawableRes public val Bluetooth: Int = R.drawable.hl_ic_bluetooth

    /** Headset or earbuds. */
    @DrawableRes public val Earbuds: Int = R.drawable.hl_ic_earbuds

    /** Wi-Fi: the signal icon's three arcs and dot in one (the CarPlay tab's Link row). */
    @DrawableRes public val Wifi: Int = R.drawable.hl_ic_wifi

    /** Signal: the two outer arcs. */
    @DrawableRes public val SignalOuter: Int = R.drawable.hl_ic_signal_outer

    /** Signal: the inner arc and the dot. */
    @DrawableRes public val SignalInner: Int = R.drawable.hl_ic_signal_inner

    /** Signal lost. */
    @DrawableRes public val SignalOff: Int = R.drawable.hl_ic_signal_off

    /** A motorcycle: riding (no call). */
    @DrawableRes public val Bike: Int = R.drawable.hl_ic_bike

    /** A bell: a call ringing. */
    @DrawableRes public val Bell: Int = R.drawable.hl_ic_bell

    /** A plain phone handset: on a call. */
    @DrawableRes public val Handset: Int = R.drawable.hl_ic_handset

    /** Information: a notice. */
    @DrawableRes public val Info: Int = R.drawable.hl_ic_info

    /** A microphone crossed out: mute the microphone. */
    @DrawableRes public val MicOff: Int = R.drawable.hl_ic_mic_off

    /** A handset put down: end or decline a call. */
    @DrawableRes public val HangUp: Int = R.drawable.hl_ic_hang_up

    /** An arrow turning back: reset to default. */
    @DrawableRes public val Reset: Int = R.drawable.hl_ic_reset

    /** The phone on its side: a picture in landscape (the phone icon turned a quarter). */
    @DrawableRes public val PhoneLandscape: Int = R.drawable.hl_ic_phone_landscape
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
        // The glow's brush is built once per size, not on every frame (drawWithCache).
        modifier.drawWithCache {
            val radiusX = size.width * GLOW_WIDTH
            val radiusY = size.height * GLOW_HEIGHT
            val centre = Offset(size.width / 2, size.height)
            val brush =
                Brush.radialGradient(
                    GLOW_STOP_INNER to colors.glowInner,
                    GLOW_STOP_MID to colors.glowMid,
                    GLOW_STOP_OUTER to colors.screen,
                    center = centre,
                    radius = radiusX,
                )
            onDrawBehind {
                drawRect(colors.screen)
                if (glow) {
                    scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = centre) {
                        drawCircle(brush, radius = radiusX, center = centre)
                    }
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

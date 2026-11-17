package com.abid.handlebarlink.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.groundShadow
import com.abid.handlebarlink.core.designsystem.theme.BikeGroundShadow
import com.abid.handlebarlink.core.designsystem.theme.BikeGroundShadowNight
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.BikeImage

/**
 * The bike on its ground (Paper "Setup"): contact and ambient shadows under the tyres; on Night also a
 * faint floor reflection from the tyre line and a soft backlight behind the bike, so a black bike stands out
 * on a black ground. [width] is the render's width; [offsetX] moves it (Welcome lets it run off the left).
 */
@Composable
fun BikeStage(
    width: Dp,
    modifier: Modifier = Modifier,
    offsetX: Dp = Dp(0f),
) {
    val night = HlTheme.variant == HlThemeVariant.NIGHT
    val height = width * RENDER_ASPECT
    Box(modifier.offset(x = offsetX).requiredSize(width, height)) {
        if (night) {
            // A radial gradient already fades softly to nothing, so no blur: blurring this ~1500 px area cost a
            // full-screen GPU pass on every frame of the page slides. Its radius takes in the blur's spread, and
            // the brush is built once per size (drawWithCache).
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val centre = Offset(size.width * BACKLIGHT_X, size.height * BACKLIGHT_Y)
                        val radius = size.width * (BACKLIGHT_RADIUS + BACKLIGHT_SPREAD)
                        val glow =
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = BACKLIGHT_ALPHA), Color.Transparent),
                                center = centre,
                                radius = radius,
                            )
                        onDrawBehind { drawCircle(glow, radius = radius, center = centre) }
                    },
            )
            Reflection(Modifier.matchParentSize())
        }
        BikeImage(
            Modifier
                .fillMaxSize()
                .groundShadow(
                    if (night) BikeGroundShadowNight else BikeGroundShadow,
                    if (night) Color.Black else HlTheme.colors.ink,
                ),
        )
    }
}

/** The render mirrored at the tyre line, faint and fading out quickly (a glossy floor). */
@Composable
private fun Reflection(modifier: Modifier) {
    BikeImage(
        modifier =
            modifier
                .graphicsLayer {
                    scaleY = -1f
                    transformOrigin = TransformOrigin(CENTRE, CONTACT_LINE)
                    alpha = REFLECTION_ALPHA
                    compositingStrategy = CompositingStrategy.Offscreen
                }.drawWithCache {
                    // Before the flip: keep only the strip just above the tyre line, which mirrors below it.
                    val strip =
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            (CONTACT_LINE - REFLECTION_DEPTH) to Color.Transparent,
                            CONTACT_LINE to Color.Black,
                        )
                    onDrawWithContent {
                        drawContent()
                        drawRect(strip, blendMode = BlendMode.DstIn)
                    }
                },
    )
}

/** The Welcome stage: the bike larger than the screen, running off the left like the artboard. */
@Composable
fun WelcomeBike(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        BikeStage(width = maxWidth * WELCOME_WIDTH, offsetX = maxWidth * WELCOME_OFFSET)
    }
}

/** The whole bike, centred ("You're set"). */
@Composable
fun WholeBike(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BikeStage(width = maxWidth * WHOLE_WIDTH)
    }
}

// Paper "Setup": the render box is 560 × 409; Welcome shows it 560 wide at x -182 of 411, "You're set"
// 395 of 411; the tyres meet the ground at 96.5 % of the box height; the reflection fades within 15 %.
private const val RENDER_ASPECT = 409f / 560f
private const val WELCOME_WIDTH = 560f / 411f
private const val WELCOME_OFFSET = -182f / 411f
private const val WHOLE_WIDTH = 395f / 411f
private const val CONTACT_LINE = 0.965f
private const val CENTRE = 0.5f
private const val REFLECTION_DEPTH = 0.15f
private const val REFLECTION_ALPHA = 0.15f
private const val BACKLIGHT_ALPHA = 0.07f
private const val BACKLIGHT_X = 0.55f
private const val BACKLIGHT_Y = 0.45f
private const val BACKLIGHT_RADIUS = 0.42f

/** How far the former blur spread the glow, now part of its radius. */
private const val BACKLIGHT_SPREAD = 0.06f

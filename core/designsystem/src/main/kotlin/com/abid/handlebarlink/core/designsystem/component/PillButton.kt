package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * The white call-to-action pill as a button of its own (a card's "Connect"), moving between states the way
 * the owner's web app's loading button does (Falcon, `components/custombutton.tsx`, Framer Motion): one
 * bouncy spring ([com.abid.handlebarlink.core.designsystem.theme.HlMotion.bounce]) for all of it; the pill's
 * width follows its content; the spinner or check pops in (scale and fade); the label swaps, the old one
 * sliding out to the left with a blur, the new one in from the right. Pressing scales it like every control.
 */
@Composable
public fun HlPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: HlPillState = HlPillState.IDLE,
) {
    val colors = HlTheme.colors
    val motion = HlTheme.motion
    val ink = colors.onAction
    val press = remember { MutableInteractionSource() }
    val slide = with(LocalDensity.current) { SLIDE.roundToPx() }
    Row(
        modifier
            .pressScale(press, motion)
            .height(HlTheme.sizes.chip)
            .clip(CircleShape)
            .background(colors.action)
            .clickable(press, LocalIndication.current, state == HlPillState.IDLE, role = Role.Button, onClick = onClick)
            .animateContentSize(motion.bounce(IntSize.VisibilityThreshold))
            .padding(horizontal = HlTheme.spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s6),
    ) {
        AnimatedVisibility(
            state != HlPillState.IDLE,
            enter = scaleIn(motion.bounce()) + fadeIn(motion.fade()),
            exit = scaleOut(motion.bounce()) + fadeOut(motion.fade()),
        ) {
            Box(Modifier.size(HlTheme.sizes.iconMicro), contentAlignment = Alignment.Center) {
                if (state == HlPillState.DONE) {
                    HlIcon(HlIcons.Check, ink, size = HlTheme.sizes.iconMicro)
                } else {
                    PillSpinner(ink)
                }
            }
        }
        AnimatedContent(
            text,
            transitionSpec = {
                (slideInHorizontally(motion.bounce(IntOffset.VisibilityThreshold)) { slide } + fadeIn(motion.fade()))
                    .togetherWith(
                        slideOutHorizontally(motion.bounce(IntOffset.VisibilityThreshold)) { -slide } +
                            fadeOut(motion.fade()),
                    ).using(SizeTransform(clip = false))
            },
            label = "pill",
        ) { label ->
            // The leaving label blurs as it goes (Framer's exit `filter: blur(4px)`).
            val blur by transition.animateFloat(label = "blur") { if (it == EnterExitState.PostExit) EXIT_BLUR else 0f }
            HlText(
                label,
                HlTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                ink,
                Modifier.graphicsLayer {
                    renderEffect =
                        if (blur > 0f) BlurEffect(blur.dp.toPx(), blur.dp.toPx()) else null
                },
                maxLines = 1,
            )
        }
    }
}

/** A small spinner: a faint track and an arc whirling three turns a second, easing out (Falcon's). */
@Composable
private fun PillSpinner(color: Color) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = SPIN,
        animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "turn",
    )
    val stroke = HlTheme.strokes.outline
    Canvas(Modifier.size(HlTheme.sizes.iconMicro).graphicsLayer { rotationZ = turn }) {
        val width = stroke.toPx()
        val arc = Size(size.width - width, size.height - width)
        val at = Offset(width / 2, width / 2)
        drawArc(color.copy(alpha = TRACK), 0f, FULL, false, at, arc, style = Stroke(width))
        drawArc(color, 0f, ARC, false, at, arc, style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** 40 px in Framer: the label's slide, in dp so it reads the same on every screen. */
private val SLIDE = 16.dp
private const val EXIT_BLUR = 4f
private const val SPIN = 1080f
private const val SPIN_MS = 1000
private const val FULL = 360f
private const val ARC = 108f
private const val TRACK = 0.15f

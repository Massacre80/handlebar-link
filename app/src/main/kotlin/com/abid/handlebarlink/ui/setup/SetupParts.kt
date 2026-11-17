package com.abid.handlebarlink.ui.setup

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIconButton
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.SignalRing
import com.abid.handlebarlink.core.designsystem.component.dashedOutline
import com.abid.handlebarlink.core.designsystem.component.pressScale
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.setup.FoundMeter
import com.abid.handlebarlink.setup.SetupProblem

/** Back and the three step dots; [step] 1–3 lights the dots up to it. */
@Composable
internal fun SetupTopBar(
    step: Int,
    onBack: () -> Unit,
) {
    val spacing = HlTheme.spacing
    Row(
        Modifier.fillMaxWidth().padding(start = spacing.s12, end = spacing.s16, top = spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HlIconButton(HlIcons.ChevronRight, stringResource(R.string.back), onBack, Modifier.rotate(HALF_TURN))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s6)) {
            repeat(STEPS) { index ->
                Box(
                    Modifier
                        .width(DOT_WIDTH)
                        .height(DOT_HEIGHT)
                        .background(
                            when {
                                index < step -> HlTheme.colors.brand

                                // Night's artboard: 14 % white; on Bento's light ground that vanishes.
                                HlTheme.variant == HlThemeVariant.NIGHT -> HlTheme.colors.raisedHi

                                else -> HlTheme.colors.track
                            },
                            RoundedCornerShape(DOT_HEIGHT),
                        ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(HlTheme.sizes.touchTarget))
    }
}

/** "STEP n OF 3" (none on Welcome and "You're set"), the title and its line; [centered] on the waiting screens. */
@Composable
internal fun SetupHeading(
    step: Int?,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val colors = HlTheme.colors
    val type = HlTheme.typography
    val align = if (centered) TextAlign.Center else TextAlign.Unspecified
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        step?.let {
            // Lime text is readable on Night only; Bento's light ground needs ink.
            val tone = if (HlTheme.variant == HlThemeVariant.NIGHT) colors.brand else colors.ink
            HlText(stringResource(R.string.setup_step_of, it, STEPS).uppercase(), type.overline, tone)
        }
        HlText(title, type.headline, colors.ink, Modifier.semantics { heading() }, textAlign = align)
        HlText(text, setupLead(), colors.inkMuted, textAlign = align)
    }
}

/** The design's running lines on setup: lead size, medium weight, tight tracking. */
@Composable
internal fun setupLead(): TextStyle {
    val type = HlTheme.typography
    return type.lead.copy(fontWeight = type.label.fontWeight, letterSpacing = type.label.letterSpacing)
}

/** A meter our scan found: the one highlighted choice ("Choose your bike"). */
@Composable
internal fun MeterCard(
    meter: FoundMeter,
    onClick: () -> Unit,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val shape = RoundedCornerShape(HlTheme.radii.card)
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(press, HlTheme.motion)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceInverse)
            .background(Brush.linearGradient(listOf(colors.brand.copy(alpha = CARD_TINT), colors.surfaceInverse)))
            .border(HlTheme.strokes.hairline, colors.surfaceInverseEdge, shape)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(start = spacing.s14, end = spacing.s16, top = spacing.s14, bottom = spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s14),
    ) {
        Box(
            Modifier.size(HlTheme.sizes.avatar).background(colors.brand, RoundedCornerShape(HlTheme.radii.nested)),
            contentAlignment = Alignment.Center,
        ) {
            HlIcon(HlIcons.Bluetooth, colors.brandInk, size = HlTheme.sizes.iconMedium)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            HlText(meter.name, HlTheme.typography.lead, colors.inkInverse, maxLines = 1)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s6),
            ) {
                SignalBars(bars(meter.rssi))
                HlText(
                    stringResource(signalText(meter.rssi)),
                    HlTheme.typography.meta,
                    colors.inkInverseMuted,
                    maxLines = 1,
                )
            }
        }
        HlIcon(HlIcons.ChevronRight, colors.inkInverse, size = HlTheme.sizes.iconTiny)
    }
}

/** Four signal bars, [lit] of them in the brand colour. */
@Composable
private fun SignalBars(lit: Int) {
    val colors = HlTheme.colors
    Canvas(Modifier.size(BARS_WIDTH, BARS_HEIGHT)) {
        val bar = size.width / (BARS * 2 - 1)
        repeat(BARS) { index ->
            val height = size.height * (index + 1) / BARS
            drawRoundRect(
                if (index < lit) colors.brand else colors.inkInverse.copy(alpha = UNLIT),
                topLeft = Offset(index * bar * 2, size.height - height),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** Lit bars for [rssi] dBm: strong above -60, good above -75, weak below. */
internal fun bars(rssi: Int): Int =
    when {
        rssi > STRONG_DBM -> BARS - 1
        rssi > GOOD_DBM -> 2
        else -> 1
    }

@StringRes
private fun signalText(rssi: Int): Int =
    when {
        rssi > STRONG_DBM -> R.string.setup_meter_strong
        rssi > GOOD_DBM -> R.string.setup_meter_good
        else -> R.string.setup_meter_weak
    }

/** The setup's numbered steps (Ready, Choose, Linking/Pairing). */
internal const val STEPS = 3
private const val HALF_TURN = 180f
private val DOT_WIDTH = 22.dp
private val DOT_HEIGHT = 4.dp
private val BARS_WIDTH = 14.dp
private val BARS_HEIGHT = 12.dp
private const val BARS = 4
private const val UNLIT = 0.24f
private const val STRONG_DBM = -60
private const val GOOD_DBM = -75
private const val CARD_TINT = 0.12f

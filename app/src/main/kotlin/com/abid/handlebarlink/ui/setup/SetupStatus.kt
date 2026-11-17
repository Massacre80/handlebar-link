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

/**
 * What stops setup, in the danger colour, and the one thing to do about it (the whole card is the button).
 * It expands in when a problem appears and out when it is fixed, keeping the last text while it leaves.
 */
@Composable
internal fun ProblemRow(
    problem: SetupProblem?,
    onFix: (SetupProblem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(problem) }
    if (problem != null) shown = problem
    val motion = HlTheme.motion
    AnimatedVisibility(
        visible = problem != null,
        modifier = modifier,
        enter = expandVertically(motion.layout(IntSize.VisibilityThreshold)) + fadeIn(motion.fade()),
        exit = shrinkVertically(motion.layout(IntSize.VisibilityThreshold)) + fadeOut(motion.fade()),
    ) {
        shown?.let { ProblemCard(it) { onFix(it) } }
    }
}

@Composable
private fun ProblemCard(
    problem: SetupProblem,
    onFix: () -> Unit,
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
            .background(colors.danger.copy(alpha = DANGER_FILL))
            .border(HlTheme.strokes.hairline, colors.danger.copy(alpha = DANGER_EDGE), shape)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onFix)
            .padding(start = spacing.s14, end = spacing.s14, top = spacing.s14, bottom = spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s12),
    ) {
        Box(
            Modifier.size(HlTheme.sizes.chip).background(colors.danger.copy(alpha = DANGER_MARK), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            HlText("!", HlTheme.typography.value, colors.danger)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            HlText(stringResource(problemText(problem)), HlTheme.typography.value, colors.ink)
            HlText(stringResource(problemDetail(problem)), HlTheme.typography.meta, colors.inkMuted)
        }
        ActionPill(stringResource(fixText(problem)))
    }
}

/** A small pill naming a row's action (the row itself is the button). */
@Composable
private fun ActionPill(text: String) {
    Box(
        Modifier
            .height(HlTheme.sizes.chip)
            .background(HlTheme.colors.action, CircleShape)
            .padding(horizontal = HlTheme.spacing.s16),
        contentAlignment = Alignment.Center,
    ) {
        HlText(
            text,
            HlTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            HlTheme.colors.onAction,
            maxLines = 1,
        )
    }
}

/**
 * The search row. SEARCHING: the ring's ticks and a bar fill over the scan's time limit while the title
 * pulses (the Home "looking" pulse). NONE_FOUND: the row turns to the danger colour with Search again.
 * ENDED: a quiet Search again under the meters found.
 */
@Composable
internal fun SearchRow(
    state: SearchState,
    onSearchAgain: () -> Unit,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val motion = HlTheme.motion
    val radius = HlTheme.radii.card
    val failed = state == SearchState.NONE_FOUND
    val searching = state == SearchState.SEARCHING
    val edge by animateColorAsState(
        if (failed) colors.danger.copy(alpha = DANGER_EDGE) else colors.lineStrong,
        motion.fade(),
    )
    val fill by animateColorAsState(
        if (failed) colors.danger.copy(alpha = DANGER_FILL) else Color.Transparent,
        motion.fade(),
    )
    val loop = rememberInfiniteTransition(label = "searching")
    val turn = loop.animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(TURN_MS, easing = LinearEasing)), "turn")
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(press, motion)
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(fill)
            .dashedOutline(edge, HlTheme.strokes.outline, radius, HlTheme.strokes.dashOn, HlTheme.strokes.dashOff)
            .then(
                if (searching) {
                    Modifier
                } else {
                    Modifier.clickable(
                        press,
                        LocalIndication.current,
                        role = Role.Button,
                        onClick = onSearchAgain,
                    )
                },
            ).padding(start = spacing.s14, end = spacing.s14, top = spacing.s14, bottom = spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s14),
    ) {
        // Ticks only: the artboard has no disc behind them.
        SignalRing(
            if (failed) colors.danger else colors.brand,
            if (failed) colors.danger.copy(alpha = DANGER_EDGE) else colors.track,
            Color.Transparent,
            progress = if (searching) SEARCH_ARC else 0f,
            modifier = Modifier.graphicsLayer { rotationZ = if (searching) turn.value else 0f },
            size = HlTheme.sizes.avatar,
        )
        AnimatedContent(
            state,
            Modifier.weight(1f),
            transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
            label = "search state",
        ) { shown ->
            SearchText(shown)
        }
        if (!searching) ActionPill(stringResource(R.string.setup_search_again))
    }
}

@Composable
private fun SearchText(state: SearchState) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
        when (state) {
            SearchState.SEARCHING -> {
                val pulse by rememberInfiniteTransition(label = "looking").animateFloat(
                    initialValue = 1f,
                    targetValue = HlTheme.motion.pulseAlpha,
                    animationSpec = infiniteRepeatable(tween(HlTheme.motion.pulseMillis), RepeatMode.Reverse),
                    label = "pulse",
                )
                HlText(
                    stringResource(R.string.setup_searching),
                    HlTheme.typography.value,
                    colors.ink,
                    Modifier.graphicsLayer { alpha = pulse },
                )
                HlText(stringResource(R.string.setup_searching_detail), HlTheme.typography.meta, colors.inkMuted)
                LoadingBar(Modifier.padding(top = spacing.s6))
            }

            SearchState.NONE_FOUND -> {
                HlText(stringResource(R.string.setup_none_found), HlTheme.typography.value, colors.danger)
                HlText(stringResource(R.string.setup_search_again_detail), HlTheme.typography.meta, colors.inkMuted)
            }

            SearchState.ENDED -> {
                HlText(stringResource(R.string.setup_not_listed), HlTheme.typography.value, colors.ink)
                HlText(stringResource(R.string.setup_search_again_detail), HlTheme.typography.meta, colors.inkMuted)
            }
        }
    }
}

/**
 * An indeterminate loading bar: one rounded segment sweeping across the track, over and over. The sweep
 * is read while drawing, so it redraws without recomposing.
 */
@Composable
private fun LoadingBar(modifier: Modifier = Modifier) {
    val colors = HlTheme.colors
    val sweep =
        rememberInfiniteTransition(label = "loading").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = FastOutSlowInEasing)),
            label = "sweep",
        )
    Canvas(modifier.fillMaxWidth().height(HlTheme.strokes.progress)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(colors.track, cornerRadius = radius)
        val segment = size.width * SEGMENT
        val left = -segment + (size.width + segment) * sweep.value
        clipRect {
            drawRoundRect(colors.brand, Offset(left, 0f), Size(segment, size.height), radius)
        }
    }
}

@StringRes
private fun problemText(problem: SetupProblem): Int =
    when (problem) {
        SetupProblem.BLUETOOTH_OFF -> R.string.setup_problem_bluetooth
        SetupProblem.LOCATION_OFF -> R.string.setup_problem_location
        SetupProblem.PERMISSION_MISSING -> R.string.setup_problem_permission
        SetupProblem.SCAN_FAILED -> R.string.setup_problem_scan
        SetupProblem.NOT_LINKED -> R.string.setup_problem_link
        SetupProblem.PAIRING_FAILED -> R.string.setup_problem_pairing
    }

@StringRes
private fun problemDetail(problem: SetupProblem): Int =
    when (problem) {
        SetupProblem.BLUETOOTH_OFF -> R.string.setup_problem_bluetooth_detail
        SetupProblem.LOCATION_OFF -> R.string.setup_problem_location_detail
        SetupProblem.PERMISSION_MISSING -> R.string.setup_problem_permission_detail
        SetupProblem.SCAN_FAILED -> R.string.setup_problem_scan_detail
        SetupProblem.NOT_LINKED -> R.string.setup_problem_link_detail
        SetupProblem.PAIRING_FAILED -> R.string.setup_problem_pairing_detail
    }

@StringRes
private fun fixText(problem: SetupProblem): Int =
    when (problem) {
        SetupProblem.BLUETOOTH_OFF, SetupProblem.LOCATION_OFF -> R.string.setup_fix_open
        SetupProblem.PERMISSION_MISSING -> R.string.access_ask
        else -> R.string.setup_fix_retry
    }

private const val SEARCH_ARC = 0.26f
private const val FULL_TURN = 360f
private const val TURN_MS = 1_400
private const val SWEEP_MS = 1_100
private const val SEGMENT = 0.4f
private const val DANGER_FILL = 0.10f
private const val DANGER_EDGE = 0.45f
private const val DANGER_MARK = 0.18f

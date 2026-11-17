package com.abid.handlebarlink.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlQuietButton
import com.abid.handlebarlink.core.designsystem.component.HlStepRow
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.component.SignalRing
import com.abid.handlebarlink.core.designsystem.component.StatusPill
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.setup.SetupProblem
import com.abid.handlebarlink.setup.SetupStep
import com.abid.handlebarlink.setup.SetupUiState
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.PermissionItem
import com.abid.handlebarlink.ui.permissions.PermissionList

/** "Link your CB350": the bike running off the left, the promise, Start. */
@Composable
internal fun WelcomePage(onStart: () -> Unit) {
    val spacing = HlTheme.spacing
    SetupPage {
        Wordmark {
            HlText(
                stringResource(R.string.setup_overline).uppercase(),
                HlTheme.typography.overline,
                HlTheme.colors.inkMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        WelcomeBike()
        Column(
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s24),
        ) {
            SetupHeading(
                null,
                stringResource(R.string.setup_welcome_title),
                stringResource(R.string.setup_welcome_text),
            )
            HlButton(
                stringResource(R.string.setup_start),
                onStart,
                Modifier.fillMaxWidth(),
                brand = true,
                chevron = true,
            )
        }
    }
}

/** Step 1, "Get the bike ready": the meter's pairing mode, then Find my bike. */
@Composable
internal fun ReadyPage(
    problem: SetupProblem?,
    actions: SetupActions,
) {
    val spacing = HlTheme.spacing
    SetupPage {
        SetupTopBar(1, actions.onBack)
        SetupHeading(
            1,
            stringResource(R.string.setup_ready_title),
            stringResource(R.string.setup_ready_text),
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s24 + spacing.s12),
        )
        Column(
            Modifier.padding(start = spacing.s16, end = spacing.s16, top = spacing.s16 * 2),
            verticalArrangement = Arrangement.spacedBy(spacing.s10),
        ) {
            READY_STEPS.forEachIndexed { index, (title, detail) ->
                HlStepRow(index + 1, stringResource(title), stringResource(detail))
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            ProblemRow(problem, actions.onFix)
            HlButton(stringResource(R.string.setup_find), actions.onFind, Modifier.fillMaxWidth(), brand = true)
            Footnote(stringResource(R.string.setup_needs))
        }
    }
}

/**
 * Step 2, "Choose your bike": the Honda meters our scan finds, the search itself, then any other named
 * devices nearby (a fallback should a meter's name ever look different).
 */
@Composable
internal fun ChoosePage(
    state: SetupUiState,
    actions: SetupActions,
) {
    val spacing = HlTheme.spacing
    SetupPage {
        SetupTopBar(2, actions.onBack)
        SetupHeading(
            2,
            stringResource(R.string.setup_choose_title),
            stringResource(R.string.setup_choose_text),
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s24 + spacing.s12),
        )
        Column(
            Modifier.padding(start = spacing.s16, end = spacing.s16, top = spacing.s16 * 2),
            verticalArrangement = Arrangement.spacedBy(spacing.s10),
        ) {
            val (meters, others) = state.meters.partition { it.honda }
            AnimatedVisibility(state.paired.isNotEmpty()) { ListLabel(stringResource(R.string.setup_paired)) }
            state.paired.forEach { meter ->
                key(meter.address) { Arriving { MeterCard(meter) { actions.onChoose(meter) } } }
            }
            AnimatedVisibility(meters.isNotEmpty()) { ListLabel(stringResource(R.string.setup_found)) }
            meters.forEach { meter ->
                key(meter.address) { Arriving { MeterCard(meter) { actions.onChoose(meter) } } }
            }
            SearchRow(
                when {
                    state.searching -> SearchState.SEARCHING
                    meters.isEmpty() && state.paired.isEmpty() -> SearchState.NONE_FOUND
                    else -> SearchState.ENDED
                },
                actions.onSearchAgain,
            )
            AnimatedVisibility(others.isNotEmpty()) {
                ListLabel(stringResource(R.string.setup_other), Modifier.padding(top = spacing.s14))
            }
            others.forEach { device ->
                key(device.address) {
                    Arriving {
                        HlValueRow(
                            value = device.name,
                            hint = null,
                            detail = device.address,
                            onClick = { actions.onChoose(device) },
                            container = HlTheme.colors.panel,
                        )
                    }
                }
            }
            ProblemRow(state.problem, actions.onFix)
        }
        Spacer(Modifier.weight(1f))
        Footnote(stringResource(R.string.setup_not_listed), Modifier.padding(top = spacing.s24))
    }
}

/** Step 3: waiting for Android's consent sheet (LINKING) or its pairing request (PAIRING). */
@Composable
internal fun WaitingPage(
    step: SetupStep,
    state: SetupUiState,
    actions: SetupActions,
) {
    val spacing = HlTheme.spacing
    val name = state.chosen?.name.orEmpty()
    SetupPage {
        SetupTopBar(STEPS, actions.onBack)
        Spacer(Modifier.weight(1f))
        WaitingRing(failed = state.problem != null, Modifier.align(Alignment.CenterHorizontally))
        SetupHeading(
            STEPS,
            if (step == SetupStep.LINKING) {
                stringResource(R.string.setup_linking_title, name)
            } else {
                stringResource(R.string.setup_pairing_title)
            },
            stringResource(if (step == SetupStep.LINKING) R.string.setup_linking_text else R.string.setup_pairing_text),
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s28),
            centered = true,
        )
        state.problem?.let {
            ProblemRow(
                it,
                { actions.onFix(it) },
                Modifier.padding(start = spacing.s16, end = spacing.s16, top = spacing.s24),
            )
        }
        // Android's sheet covers the lower half: the ring and the words sit above it.
        Spacer(Modifier.weight(WAITING_BOTTOM_WEIGHT))
    }
}

/** "You're set": the whole bike and the permissions it still needs; Home opens once they are allowed. */
@Composable
internal fun DonePage(
    name: String,
    access: SetupAccess,
    actions: SetupActions,
) {
    val spacing = HlTheme.spacing
    SetupPage {
        Wordmark { StatusPill(stringResource(R.string.pill_connected), on = true) }
        Spacer(Modifier.weight(1f))
        WholeBike()
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(start = spacing.s16, end = spacing.s16, top = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s20),
        ) {
            SetupHeading(
                null,
                stringResource(R.string.setup_done_title),
                stringResource(R.string.setup_done_text, name),
                Modifier.padding(horizontal = spacing.s8),
            )
            PermissionList(
                listOf(
                    PermissionItem(
                        R.string.permission_notifications,
                        R.string.permission_notifications_why,
                        access.notifications,
                        actions.onNotifications,
                    ),
                    PermissionItem(
                        R.string.permission_location,
                        R.string.permission_location_why,
                        access.location,
                        actions.onLocation,
                    ),
                ),
            )
            // No button: Home opens by itself once both are allowed (owner, 2026-11-17). A refusal for good
            // must not trap the rider, so then they may continue without it.
            if (access.blocked) {
                HlQuietButton(stringResource(R.string.setup_continue_without), actions.onHome)
            } else {
                Footnote(stringResource(R.string.setup_done_auto))
            }
        }
    }
}

/** A list's overline ("FOUND NEARBY"). */
@Composable
private fun ListLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    HlText(
        text.uppercase(),
        HlTheme.typography.caps,
        HlTheme.colors.inkMuted,
        modifier.padding(horizontal = HlTheme.spacing.s8),
    )
}

/** The bike's name centred at the top, with [below] under it (the overline, or the link pill). */
@Composable
private fun Wordmark(below: @Composable () -> Unit) {
    val spacing = HlTheme.spacing
    Column(
        Modifier.fillMaxWidth().padding(top = spacing.s18),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        HlText(stringResource(R.string.bike_model), HlTheme.typography.wordmark, HlTheme.colors.ink)
        below()
    }
}

/** A centred muted line at the bottom, a touch target tall like the artboards. */
@Composable
private fun Footnote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxWidth().heightIn(min = HlTheme.sizes.touchTarget).padding(horizontal = HlTheme.spacing.s24),
        contentAlignment = Alignment.Center,
    ) {
        HlText(text, HlTheme.typography.value, HlTheme.colors.inkMuted)
    }
}

/**
 * The Bluetooth mark in the signal ring, its lit arc turning while Android works; [failed] stops it and
 * turns it to the danger colour (pairing did not finish).
 */
@Composable
private fun WaitingRing(
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val motion = HlTheme.motion
    val tone by animateColorAsState(if (failed) colors.danger else colors.brand, motion.fade())
    val turn = remember { Animatable(0f) }
    LaunchedEffect(failed) {
        // One linear turn after another until it fails or leaves (cancellation ends the loop).
        while (!failed) {
            turn.animateTo(FULL_TURN, tween(TURN_MS, easing = LinearEasing))
            turn.snapTo(0f)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        SignalRing(
            tone,
            colors.track,
            colors.raisedLo,
            progress = if (failed) 1f else WAITING_ARC,
            modifier = Modifier.graphicsLayer { rotationZ = turn.value },
            size = HlTheme.sizes.gauge,
        )
        HlIcon(HlIcons.Bluetooth, tone, size = HlTheme.sizes.icon * 2)
    }
}

/**
 * [content] fades and grows in the first time it is shown (a meter the scan just found). Previews and
 * screenshots show it settled: they capture the first frame.
 */
@Composable
private fun Arriving(content: @Composable () -> Unit) {
    val motion = HlTheme.motion
    val inspecting = LocalInspectionMode.current
    val seen = remember { MutableTransitionState(inspecting).apply { targetState = true } }
    AnimatedVisibility(
        seen,
        enter = fadeIn(motion.fade()) + expandVertically(motion.layout(IntSize.VisibilityThreshold)),
    ) {
        content()
    }
}

/** The meter's pairing mode (docs/protocol/SAB_PROTOCOL.md "Putting the meter into pairing mode"). */
private val READY_STEPS =
    listOf(
        R.string.setup_ready_1 to R.string.setup_ready_1_detail,
        R.string.setup_ready_2 to R.string.setup_ready_2_detail,
        R.string.setup_ready_3 to R.string.setup_ready_3_detail,
        R.string.setup_ready_4 to R.string.setup_ready_4_detail,
    )
private const val WAITING_BOTTOM_WEIGHT = 2.5f
private const val WAITING_ARC = 0.75f
private const val FULL_TURN = 360f
private const val TURN_MS = 2_400

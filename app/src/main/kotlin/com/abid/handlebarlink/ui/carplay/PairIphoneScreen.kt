package com.abid.handlebarlink.ui.carplay

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlCard
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlQuietButton
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlStepRow
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.SignalRing
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.navigation.Move
import com.abid.handlebarlink.ui.navigation.pageTransition
import com.abid.handlebarlink.ui.setup.Footnote
import com.abid.handlebarlink.ui.setup.ProblemCard
import com.abid.handlebarlink.ui.setup.STEPS
import com.abid.handlebarlink.ui.setup.SetupHeading
import com.abid.handlebarlink.ui.setup.SetupPage
import com.abid.handlebarlink.ui.setup.SetupTopBar
import com.abid.handlebarlink.ui.setup.WaitingRing
import com.abid.handlebarlink.ui.setup.setupListTop
import com.abid.handlebarlink.ui.setup.setupPanes
import java.util.Locale

/**
 * Pairing an iPhone inside the app (Paper "CarPlay — flow v2", iPhone 1–3), built from the setup screens' parts (back,
 * step dashes, "STEP X OF 3", numbered step cards, the lime pill, the Bluetooth dial). Steps slide like setup's.
 * [bikeName] is what "Rename" names this phone (the bike's model).
 */
@Composable
fun PairIphoneScreen(
    state: PairIphoneUiState,
    bikeName: String,
    actions: PairIphoneActions,
) {
    val motion = HlTheme.motion
    AnimatedContent(
        targetState = state.step,
        transitionSpec = {
            pageTransition(if (targetState > initialState) Move.TAB_FORWARD else Move.TAB_BACKWARD, motion)
        },
        label = "pairing step",
    ) { step ->
        when (step) {
            PairStep.STEPS -> StepsPage(state, actions)
            PairStep.WAITING -> WaitingPage(state, bikeName, actions)
            PairStep.PAIRING -> PairingPage(state, actions)
            PairStep.PAIRED -> PairedPage(state, actions)
        }
    }
}

/** iPhone 1, "Pair your iPhone": what happens, then "Make this phone visible". */
@Composable
private fun StepsPage(
    state: PairIphoneUiState,
    actions: PairIphoneActions,
) {
    val spacing = HlTheme.spacing
    val panes = setupPanes()
    val visible: @Composable () -> Unit = {
        Column(
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            Problem(state, actions)
            HlButton(
                stringResource(R.string.pair_make_visible),
                actions.onMakeVisible,
                Modifier.fillMaxWidth(),
                brand = true,
            )
            Footnote(stringResource(R.string.pair_needs))
        }
    }
    SetupPage(
        start = {
            SetupTopBar(1, actions.onBack)
            SetupHeading(
                1,
                stringResource(R.string.pair_steps_title),
                stringResource(R.string.pair_steps_text),
                Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s24 + spacing.s12),
            )
            // On its side the button stays under the heading, on screen while the steps fill the other pane.
            if (panes) {
                Spacer(Modifier.weight(1f))
                visible()
            }
        },
    ) {
        Column(
            Modifier.padding(start = spacing.s16, end = spacing.s16, top = setupListTop()),
            verticalArrangement = Arrangement.spacedBy(spacing.s10),
        ) {
            PAIR_STEPS.forEachIndexed { index, (title, detail) ->
                HlStepRow(index + 1, stringResource(title), stringResource(detail))
            }
        }
        if (!panes) {
            Spacer(Modifier.weight(1f))
            visible()
        }
    }
}

/** iPhone 2a, "Waiting for your iPhone": the dial, this phone's name and the time it stays visible. */
@Composable
private fun WaitingPage(
    state: PairIphoneUiState,
    bikeName: String,
    actions: PairIphoneActions,
) {
    val spacing = HlTheme.spacing
    val visible = (state.secondsLeft ?: 0) > 0
    val panes = setupPanes()
    SetupPage(
        start = {
            SetupTopBar(2, actions.onBack)
            Spacer(Modifier.weight(1f))
            WaitingRing(failed = state.problem != null, Modifier.align(Alignment.CenterHorizontally))
            if (panes) Spacer(Modifier.weight(1f))
        },
    ) {
        if (panes) Spacer(Modifier.weight(1f))
        SetupHeading(
            2,
            stringResource(R.string.pair_waiting_title),
            stringResource(R.string.pair_waiting_text),
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s28),
            centered = true,
        )
        Column(
            Modifier.padding(start = spacing.s16, end = spacing.s16, top = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            PhoneCard(
                HlIcons.Bluetooth,
                state.localName ?: stringResource(R.string.pair_this_phone),
                if (visible) {
                    stringResource(R.string.pair_visible_left, minutesSeconds(state.secondsLeft ?: 0))
                } else {
                    stringResource(R.string.pair_not_visible)
                },
                lit = visible,
            )
            Problem(state, actions)
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(horizontal = spacing.s24)) {
            HlQuietButton(stringResource(R.string.pair_visible_again), actions.onMakeVisible)
            // Setting the name is public (BluetoothAdapter.setName, BLUETOOTH_CONNECT): offered once it differs.
            if (state.localName != null && state.localName != bikeName) {
                HlQuietButton(stringResource(R.string.pair_rename, bikeName), actions.onRename)
            }
        }
    }
}

/** iPhone 2b, "Check the code": Android's code dialog is on screen. */
@Composable
private fun PairingPage(
    state: PairIphoneUiState,
    actions: PairIphoneActions,
) {
    val spacing = HlTheme.spacing
    val panes = setupPanes()
    SetupPage(
        start = {
            SetupTopBar(2, actions.onBack)
            Spacer(Modifier.weight(1f))
            WaitingRing(failed = false, Modifier.align(Alignment.CenterHorizontally))
            if (panes) Spacer(Modifier.weight(1f))
        },
    ) {
        if (panes) Spacer(Modifier.weight(1f))
        SetupHeading(
            2,
            stringResource(R.string.pair_code_title),
            stringResource(R.string.pair_code_text),
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s28),
            centered = true,
        )
        PhoneCard(
            HlIcons.Phone,
            state.otherName ?: stringResource(R.string.pair_a_phone),
            stringResource(R.string.pair_pairing),
            lit = true,
            modifier = Modifier.padding(start = spacing.s16, end = spacing.s16, top = spacing.s24),
        )
        // Android's dialog covers the lower half: the dial and the words sit above it (as setup's waiting screen).
        Spacer(Modifier.weight(if (panes) 1f else PAIRING_BOTTOM_WEIGHT))
    }
}

/** iPhone 3, "Paired": a check, the iPhone, Start CarPlay and Done. */
@Composable
private fun PairedPage(
    state: PairIphoneUiState,
    actions: PairIphoneActions,
) {
    val spacing = HlTheme.spacing
    val colors = HlTheme.colors
    val panes = setupPanes()
    SetupPage(
        start = {
            SetupTopBar(STEPS, actions.onBack)
            Spacer(Modifier.weight(1f))
            Box(Modifier.align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                SignalRing(colors.brand, colors.track, colors.raisedLo, progress = null, size = HlTheme.sizes.gauge)
                HlIcon(HlIcons.Check, colors.brand, size = HlTheme.sizes.icon * 2)
            }
            if (panes) Spacer(Modifier.weight(1f))
        },
    ) {
        if (panes) Spacer(Modifier.weight(1f))
        SetupHeading(
            STEPS,
            stringResource(R.string.pair_paired_title),
            stringResource(R.string.pair_paired_text),
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s28),
            centered = true,
        )
        PhoneCard(
            HlIcons.Phone,
            state.paired?.name.orEmpty(),
            stringResource(R.string.pair_paired_line),
            lit = true,
            modifier = Modifier.padding(start = spacing.s16, end = spacing.s16, top = spacing.s24),
        )
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(start = spacing.s24, end = spacing.s24, top = spacing.s24),
            verticalArrangement = Arrangement.spacedBy(spacing.s8),
        ) {
            HlButton(
                stringResource(R.string.carplay_action_start),
                actions.onStartCarPlay,
                Modifier.fillMaxWidth(),
                brand = true,
            )
            HlSecondaryButton(stringResource(R.string.pair_done), actions.onDone)
        }
    }
}

/** A phone: an icon block (brand when [lit]), its name and one line. */
@Composable
private fun PhoneCard(
    @DrawableRes icon: Int,
    name: String,
    line: String,
    lit: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    HlCard(modifier.fillMaxWidth(), radius = HlTheme.radii.card) {
        Row(
            Modifier.padding(spacing.s14),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s14),
        ) {
            Box(
                Modifier
                    .size(HlTheme.sizes.avatar)
                    .background(if (lit) colors.brand else colors.raisedLo, RoundedCornerShape(HlTheme.radii.nested)),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(icon, if (lit) colors.brandInk else colors.inkMuted, size = HlTheme.sizes.iconMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
                HlText(name, HlTheme.typography.lead, colors.ink, maxLines = 1)
                HlText(line, HlTheme.typography.meta, colors.inkMuted, maxLines = 2)
            }
        }
    }
}

/** The pairing's problem, if any, in the danger colour with its one fix. */
@Composable
private fun Problem(
    state: PairIphoneUiState,
    actions: PairIphoneActions,
) {
    val problem = state.problem ?: return
    val name = state.otherName ?: stringResource(R.string.pair_a_phone)
    val (title, detail) =
        when (problem) {
            PairProblem.NOT_ALLOWED -> {
                stringResource(R.string.pair_problem_permission) to
                    stringResource(R.string.pair_problem_permission_detail)
            }

            PairProblem.BLUETOOTH_OFF -> {
                stringResource(R.string.pair_problem_bluetooth) to
                    stringResource(R.string.pair_problem_bluetooth_detail)
            }

            PairProblem.NOT_VISIBLE -> {
                stringResource(R.string.pair_problem_hidden) to
                    stringResource(R.string.pair_problem_hidden_detail)
            }

            PairProblem.FAILED -> {
                stringResource(R.string.pair_problem_failed, name) to
                    stringResource(R.string.pair_problem_failed_detail)
            }

            PairProblem.NOT_AN_IPHONE -> {
                stringResource(R.string.pair_problem_not_iphone, name) to
                    stringResource(R.string.pair_problem_not_iphone_detail)
            }
        }
    val fix = if (problem == PairProblem.NOT_ALLOWED) R.string.access_ask else R.string.setup_fix_retry
    ProblemCard(title, detail, stringResource(fix)) {
        if (problem == PairProblem.NOT_ALLOWED) actions.onAllow() else actions.onMakeVisible()
    }
}

/** [seconds] as "m:ss". */
internal fun minutesSeconds(seconds: Long): String =
    "%d:%02d".format(Locale.ROOT, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

/** What the rider does, in order (CarLink's pairing: this phone visible, the iPhone pairs from its settings). */
private val PAIR_STEPS =
    listOf(
        R.string.pair_step_1 to R.string.pair_step_1_detail,
        R.string.pair_step_2 to R.string.pair_step_2_detail,
        R.string.pair_step_3 to R.string.pair_step_3_detail,
    )
private const val SECONDS_PER_MINUTE = 60L
private const val PAIRING_BOTTOM_WEIGHT = 2.5f

/** Sample states for previews and screenshot tests (Paper "iPhone 1–3"). */
internal val PreviewPairSteps = PairIphoneUiState()
internal val PreviewPairWaiting = PairIphoneUiState(PairStep.WAITING, localName = "POCO F1", secondsLeft = 287)
internal val PreviewPairCode = PairIphoneUiState(PairStep.PAIRING, otherName = "Abid's iPhone", secondsLeft = 240)
internal val PreviewPairPaired =
    PairIphoneUiState(
        PairStep.PAIRED,
        otherName = "Abid's iPhone",
        paired = PairedIphone("20:00:00:00:00:01", "Abid's iPhone"),
    )
internal val PreviewPairActions = PairIphoneActions({}, {}, {}, {}, {}, {})

@Preview(widthDp = 411, heightDp = 856)
@Composable
private fun PairWaitingPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { PairIphoneScreen(PreviewPairWaiting, "CB350RS", PreviewPairActions) }
    }
}

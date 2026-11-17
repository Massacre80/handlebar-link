package com.abid.handlebarlink.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.setup.FoundMeter
import com.abid.handlebarlink.setup.SetupProblem
import com.abid.handlebarlink.setup.SetupStep
import com.abid.handlebarlink.setup.SetupUiState
import com.abid.handlebarlink.ui.navigation.Move
import com.abid.handlebarlink.ui.navigation.pageTransition
import com.abid.handlebarlink.ui.permissions.Access

/**
 * First-time setup (Paper "Setup"): one screen per [SetupStep]. Steps slide like tabs, forward and back by
 * their order; Android's own consent and pairing sheets appear over the waiting screen.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    notifications: Access,
    actions: SetupActions,
) {
    val motion = HlTheme.motion
    AnimatedContent(
        targetState = state.step,
        transitionSpec = {
            pageTransition(if (targetState > initialState) Move.TAB_FORWARD else Move.TAB_BACKWARD, motion)
        },
        label = "setup step",
    ) { step ->
        when (step) {
            SetupStep.WELCOME -> WelcomePage(actions.onStart)
            SetupStep.READY -> ReadyPage(state.problem, actions)
            SetupStep.CHOOSE -> ChoosePage(state, actions)
            SetupStep.LINKING, SetupStep.PAIRING -> WaitingPage(step, state, actions)
            SetupStep.DONE -> DonePage(state.chosen?.name ?: state.linkedAddress.orEmpty(), notifications, actions)
        }
    }
}

/**
 * A setup page: the full screen inside the system bars, at most the content width, scrolling when the text
 * or the screen does not fit (docs/LESSONS.md 22). Its height is at least the screen's, so a weighted
 * spacer pushes the actions to the bottom like the artboards.
 */
@Composable
internal fun SetupPage(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .wrapContentWidth()
                .widthIn(max = HlTheme.sizes.contentMax)
                .padding(bottom = HlTheme.spacing.s24),
            content = content,
        )
    }
}

private val PreviewMeter = FoundMeter("HONDA BTU 2040", "AA:BB:CC:DD:EE:FF", rssi = -55)
private val PreviewActions = SetupActions({}, {}, {}, {}, {}, {}, {}, {})

@Composable
private fun SetupPreview(
    variant: HlThemeVariant,
    state: SetupUiState,
) {
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) { SetupScreen(state, Access.ASK, PreviewActions) }
    }
}

@Preview(widthDp = 411, heightDp = 856)
@Composable
private fun SetupSearchingPreview() =
    SetupPreview(HlThemeVariant.NIGHT, SetupUiState(SetupStep.CHOOSE, meters = listOf(PreviewMeter), searching = true))

@Preview(widthDp = 411, heightDp = 856)
@Composable
private fun SetupNoneFoundPreview() = SetupPreview(HlThemeVariant.NIGHT, SetupUiState(SetupStep.CHOOSE))

@Preview(widthDp = 411, heightDp = 856)
@Composable
private fun SetupPairingFailedPreview() =
    SetupPreview(
        HlThemeVariant.NIGHT,
        SetupUiState(SetupStep.PAIRING, chosen = PreviewMeter, problem = SetupProblem.PAIRING_FAILED),
    )

@Preview(widthDp = 411, heightDp = 856)
@Composable
private fun SetupReadyProblemBentoPreview() =
    SetupPreview(HlThemeVariant.BENTO, SetupUiState(SetupStep.READY, problem = SetupProblem.BLUETOOTH_OFF))

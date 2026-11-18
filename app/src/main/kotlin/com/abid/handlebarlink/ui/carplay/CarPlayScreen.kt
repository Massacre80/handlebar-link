package com.abid.handlebarlink.ui.carplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ride.RideService
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.rememberCarPlayAccess
import java.time.Instant

/**
 * The CarPlay tab with its view model. Turning the switch on asks for CarPlay's permissions when they are missing
 * (the same runtime flow as the Permissions page), and starts the ride service once they are allowed, so a ride
 * that had ended picks CarPlay up while the app is on screen (a foreground service may only start then).
 */
@Composable
fun CarPlayEntry(contentPadding: PaddingValues) {
    val viewModel: CarPlayViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val access = rememberCarPlayAccess { viewModel.recheckPermission() }
    CarPlayScreen(
        state,
        onToggle = {
            val on = !state.enabled
            viewModel.setEnabled(on)
            if (on) {
                when (access.status) {
                    Access.GRANTED -> {
                        RideService.start(context)
                    }

                    Access.ASK -> {
                        access.act()
                    }

                    // Refused for good: the status line points to Settings › Permissions instead of jumping there.
                    Access.BLOCKED -> {}
                }
            }
        },
        contentPadding = contentPadding,
    )
}

/**
 * The CarPlay tab: what runs CarPlay, the "Handlebar Link CarPlay (beta)" switch (an [HlValueRow] whose hint is
 * On or Off), and under its name the one status line. Off keeps today's "Runs in CarLink for now". The page
 * scrolls when it runs out of height (docs/LESSONS.md 22).
 */
@Composable
fun CarPlayScreen(
    state: CarPlayUiState,
    onToggle: () -> Unit,
    contentPadding: PaddingValues,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(contentPadding),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = spacing.s24, vertical = spacing.s16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s12, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.size(HlTheme.sizes.controlLarge).background(colors.panel, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HlIcon(HlIcons.CarPlay, colors.ink)
            }
            HlText(
                stringResource(R.string.tab_carplay),
                HlTheme.typography.title,
                colors.ink,
                Modifier.semantics { heading() },
            )
            HlText(
                stringResource(if (state.enabled) R.string.carplay_beta_note else R.string.later_carplay),
                HlTheme.typography.body,
                colors.inkMuted,
                Modifier.widthIn(max = HlTheme.sizes.contentMax),
                textAlign = TextAlign.Center,
            )
            val switchState = stringResource(if (state.enabled) R.string.carplay_on else R.string.carplay_off)
            HlGroup(Modifier.widthIn(max = HlTheme.sizes.contentMax).padding(top = spacing.s8)) {
                HlValueRow(
                    value = stringResource(R.string.carplay_switch),
                    hint = switchState,
                    modifier = Modifier.semantics { stateDescription = switchState },
                    detail = state.line.words(),
                    onClick = onToggle,
                    container = Color.Transparent,
                    // A live state, as rows mark one ("Connected" on Add device); brand lime would vanish on Bento
                    // (docs/LESSONS.md 31).
                    hintColor = if (state.enabled) colors.success else Color.Unspecified,
                )
            }
        }
    }
}

/** Sample states for previews and screenshot tests: off, and connecting. */
internal val PreviewCarPlayOff = CarPlayUiState()

internal val PreviewCarPlayConnecting =
    CarPlayUiState(
        enabled = true,
        line =
            carPlayLine(
                CarPlayStatus.Engine(CarPlayState.Connecting(ConnectStep.DIALING_PHONE)),
                Instant.EPOCH,
            ),
    )

@Preview(name = "CarPlay · Night", widthDp = 411, heightDp = 600)
@Composable
private fun CarPlayNightPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { CarPlayScreen(PreviewCarPlayOff, {}, PaddingValues()) }
    }
}

@Preview(name = "CarPlay · Bento", widthDp = 411, heightDp = 600)
@Composable
private fun CarPlayBentoPreview() {
    HlTheme(HlThemeVariant.BENTO) {
        HlBackground(Modifier.fillMaxSize()) { CarPlayScreen(PreviewCarPlayConnecting, {}, PaddingValues()) }
    }
}

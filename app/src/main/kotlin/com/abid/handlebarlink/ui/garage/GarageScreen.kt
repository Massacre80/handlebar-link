package com.abid.handlebarlink.ui.garage

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlDangerButton
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlQuietButton
import com.abid.handlebarlink.core.designsystem.component.HlSection
import com.abid.handlebarlink.core.designsystem.component.HlTag
import com.abid.handlebarlink.core.designsystem.component.HlTagTone
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.centred
import com.abid.handlebarlink.garage.Colourway
import com.abid.handlebarlink.garage.Due
import com.abid.handlebarlink.garage.DueState
import com.abid.handlebarlink.garage.GarageHero
import com.abid.handlebarlink.garage.PaperKind
import com.abid.handlebarlink.garage.ReminderKind
import com.abid.handlebarlink.garage.ServiceEntry
import com.abid.handlebarlink.garage.ServiceKind
import com.abid.handlebarlink.garage.grouped
import com.abid.handlebarlink.garage.serviceKindName
import com.abid.handlebarlink.ui.BikeImage
import com.abid.handlebarlink.ui.frame.HeroPanes
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.frame.pageTop
import com.abid.handlebarlink.ui.frame.rememberPageScrollState
import java.time.LocalDate

/**
 * The Garage tab (Paper "Garage 1–4", "Garage 2L"): the bike with its state, the next service, what comes up, the
 * service log and the bike's details. Upright one column of at most the content width; on its side the hero in the
 * first pane and everything else in the second ([HeroPanes]).
 */
@Composable
fun GarageScreen(
    state: GarageUiState,
    actions: GarageActions,
    contentPadding: PaddingValues,
) {
    if (!state.loaded) return
    val spacing = HlTheme.spacing
    if (LocalWindowLayout.current == WindowLayout.PANES) {
        HeroPanes(
            contentPadding,
            // One column, so the hero keeps its own spacing (the pane spaces its children by 12 dp).
            hero = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Hero(state, landscape = true) } },
            side = {
                Column {
                    if (hasHeroActions(state)) HeroActions(state, actions, Modifier.padding(bottom = spacing.s14))
                    Body(state, actions, landscape = true)
                }
            },
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScrollState())
            .windowInsetsPadding(WindowInsets.pageTop)
            .padding(contentPadding)
            .wrapContentWidth()
            .widthIn(max = HlTheme.sizes.contentMax)
            .padding(start = spacing.s16, end = spacing.s16, top = spacing.s4, bottom = spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Hero(state, landscape = false)
        // 24 dp from the hero's words to what follows; an action group then keeps 14 dp to the card under it (owner,
        // 2026-11-20; polish branch's action-group token at the merge).
        if (hasHeroActions(state)) {
            HeroActions(state, actions, Modifier.padding(top = spacing.s24, bottom = spacing.s14))
        } else {
            Spacer(Modifier.height(spacing.s24))
        }
        Body(state, actions, landscape = false)
    }
}

/** The bike, the state tag, the name and one line (Paper "Garage 2": 8, 12 and 8 dp apart). */
@Composable
private fun ColumnScope.Hero(
    state: GarageUiState,
    landscape: Boolean,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    BikeImage(
        Modifier
            .fillMaxWidth()
            .widthIn(max = if (landscape) LANDSCAPE_BIKE_WIDTH_DP.dp else HlTheme.sizes.contentMax)
            .aspectRatio(if (landscape) LANDSCAPE_BIKE_ASPECT else BIKE_ASPECT),
    )
    heroTag(state.hero)?.let { (text, tone) ->
        HlTag(text, Modifier.padding(top = spacing.s8), tone, dot = true)
    }
    HlText(
        stringResource(R.string.garage_bike_name),
        HlTheme.typography.headline.centred(),
        colors.ink,
        Modifier.padding(top = spacing.s12).semantics { heading() },
    )
    HlText(
        heroDetail(state),
        HlTheme.typography.value
            .copy(fontWeight = FontWeight.Medium)
            .centred(),
        colors.inkMuted,
        Modifier.padding(top = spacing.s8).widthIn(max = HERO_DETAIL_MAX_DP.dp),
    )
}

/** The hero's tag: never the service's state, which its gauge shows (GarageHero). */
@Composable
private fun heroTag(hero: GarageHero): Pair<String, HlTagTone>? =
    when (hero) {
        GarageHero.Empty -> {
            stringResource(R.string.garage_tag_nothing) to HlTagTone.QUIET
        }

        GarageHero.AllGood -> {
            stringResource(R.string.garage_tag_all_good) to HlTagTone.BRAND
        }

        GarageHero.ServiceOnly -> {
            null
        }

        is GarageHero.Attention -> {
            val name = shortName(hero.due)
            if (hero.due.state == DueState.OVERDUE) {
                stringResource(R.string.garage_tag_overdue, name) to HlTagTone.DANGER
            } else {
                stringResource(R.string.garage_tag_soon, name) to HlTagTone.BRAND
            }
        }
    }

@Composable
private fun shortName(due: Due): String =
    due.name ?: stringResource(
        when (due.kind) {
            ReminderKind.INSURANCE -> R.string.garage_short_insurance
            ReminderKind.PUC -> R.string.garage_short_puc
            ReminderKind.RC -> R.string.garage_short_rc
            ReminderKind.CHAIN -> R.string.garage_short_chain
            ReminderKind.SERVICE, ReminderKind.CUSTOM -> R.string.garage_short_custom
        },
    )

/** The hero's line: what to do about an overdue reminder, the intro when empty, else the colourway and the meter. */
@Composable
private fun heroDetail(state: GarageUiState): String {
    val hero = state.hero
    val overdue = (hero as? GarageHero.Attention)?.due?.takeIf { it.state == DueState.OVERDUE }
    val day = overdue?.dueDay?.let { shortDate(it, state.today) } ?: ""
    return when {
        hero == GarageHero.Empty -> {
            stringResource(R.string.garage_empty_detail)
        }

        overdue?.kind == ReminderKind.INSURANCE -> {
            stringResource(R.string.garage_overdue_insurance, day)
        }

        overdue?.kind == ReminderKind.PUC -> {
            stringResource(R.string.garage_overdue_puc, day)
        }

        overdue?.kind == ReminderKind.RC -> {
            stringResource(R.string.garage_overdue_rc, day)
        }

        overdue?.kind == ReminderKind.CHAIN -> {
            stringResource(R.string.garage_overdue_chain, km(state.data.chain.km))
        }

        overdue != null -> {
            stringResource(R.string.garage_overdue_custom, dueName(overdue))
        }

        else -> {
            stringResource(
                R.string.garage_detail,
                stringResource(Colourway.of(state.data.colourway).short),
                stringResource(if (state.meter != null) R.string.garage_meter_linked else R.string.garage_meter_none),
            )
        }
    }
}

private fun hasHeroActions(state: GarageUiState): Boolean =
    state.hero == GarageHero.Empty ||
        (state.hero as? GarageHero.Attention)?.due?.state == DueState.OVERDUE

/**
 * The action the hero asks for, as the brand button (owner: primary is what the rider must do): empty, log the last
 * service (and, lesser, add papers); overdue, the renewal or the job. Stacked 8 dp apart.
 */
@Composable
private fun HeroActions(
    state: GarageUiState,
    actions: GarageActions,
    modifier: Modifier,
) {
    val hero = state.hero
    val overdue = (hero as? GarageHero.Attention)?.due?.takeIf { it.state == DueState.OVERDUE }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        if (hero == GarageHero.Empty) {
            HlButton(stringResource(R.string.garage_log_last), actions.onLogService, brand = true)
            HlQuietButton(stringResource(R.string.garage_add_papers), { actions.onPaper(PaperKind.INSURANCE) })
        } else if (overdue != null) {
            val (label, onClick) = overdueAction(overdue, actions)
            HlButton(stringResource(label), onClick, brand = true)
        }
    }
}

private fun overdueAction(
    due: Due,
    actions: GarageActions,
): Pair<Int, () -> Unit> =
    when (due.kind) {
        ReminderKind.INSURANCE -> R.string.garage_renewed to { actions.onPaper(PaperKind.INSURANCE) }
        ReminderKind.PUC -> R.string.garage_renewed to { actions.onPaper(PaperKind.PUC) }
        ReminderKind.RC -> R.string.garage_renewed to { actions.onPaper(PaperKind.RC) }
        ReminderKind.CHAIN -> R.string.garage_chain_done to actions.onChain
        ReminderKind.SERVICE -> R.string.garage_log_this to actions.onLogService
        ReminderKind.CUSTOM -> R.string.garage_custom_next to { actions.onReminder(customIdOf(due)) }
    }

internal fun customIdOf(due: Due): Long? = due.id.substringAfter(':').toLongOrNull()

/** Everything under the hero, 24 dp between sections (Paper "Garage 2"). */
@Composable
private fun Body(
    state: GarageUiState,
    actions: GarageActions,
    landscape: Boolean,
) {
    val spacing = HlTheme.spacing
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.s24)) {
        if (state.hero == GarageHero.Empty) {
            Intro()
        } else {
            NextServiceCard(state, actions, landscape)
            ComingUp(state, actions)
            // Nothing to list and the card already asks for the first entry: no empty section.
            if (state.data.log.isNotEmpty() || !serviceAsks(state)) ServiceLog(state, actions)
        }
        BikeSection(state, actions)
        if (state.meter != null) HlDangerButton(stringResource(R.string.garage_forget), actions.onForget)
    }
}

/** Paper "Garage 2": the render 379 x 250, fitted. */
private const val BIKE_ASPECT = 379f / 250f

/** Paper "Garage 2L": the render 300 x 170. */
private const val LANDSCAPE_BIKE_ASPECT = 300f / 170f
private const val LANDSCAPE_BIKE_WIDTH_DP = 300

/** Paper "Garage 2": the hero's line keeps to 300 dp. */
private const val HERO_DETAIL_MAX_DP = 300

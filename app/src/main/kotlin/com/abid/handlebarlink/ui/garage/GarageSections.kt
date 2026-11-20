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
import androidx.compose.foundation.rememberScrollState
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
import java.time.LocalDate

// The Garage tab's sections under the next service card (Paper "Garage 2"): Coming up, Service log, Bike.

/** Nothing added yet: what Garage does, one row each, information only. */
@Composable
internal fun Intro() {
    HlGroup {
        IntroRow(HlIcons.Garage, R.string.garage_intro_service, R.string.garage_intro_service_detail)
        HlGroupDivider()
        IntroRow(R.drawable.garage_ic_paper, R.string.garage_intro_papers, R.string.garage_intro_papers_detail)
        HlGroupDivider()
        IntroRow(R.drawable.garage_ic_chain, R.string.garage_intro_own, R.string.garage_intro_own_detail)
    }
}

@Composable
private fun IntroRow(
    @DrawableRes icon: Int,
    title: Int,
    detail: Int,
) = GarageRow(icon, stringResource(title), stringResource(detail), trailing = null, onClick = null)

/** The chain, the papers and the rider's own reminders, soonest first, then "Add a reminder". */
@Composable
internal fun ComingUp(
    state: GarageUiState,
    actions: GarageActions,
) {
    HlSection(stringResource(R.string.garage_coming_up)) {
        state.status.upcoming.forEachIndexed { index, due ->
            if (index > 0) HlGroupDivider()
            val overdue = due.state == DueState.OVERDUE
            GarageRow(
                dueIcon(due.kind),
                dueName(due),
                dueDetail(due, state.data.chain.km),
                dueWhen(due),
                onClick = { openDue(due, actions) },
                tone = if (overdue) RowTone.DANGER else RowTone.PLAIN,
                trailingColor = if (overdue) HlTheme.colors.danger else Color.Unspecified,
            )
        }
        HlGroupDivider()
        GarageRow(
            HlIcons.Plus,
            stringResource(R.string.garage_add_reminder),
            detail = null,
            trailing = null,
            onClick = { actions.onReminder(null) },
            tone = RowTone.ADD,
        )
    }
}

private fun openDue(
    due: Due,
    actions: GarageActions,
) = when (due.kind) {
    ReminderKind.SERVICE -> actions.onServiceInterval()
    ReminderKind.CHAIN -> actions.onChain()
    ReminderKind.INSURANCE -> actions.onPaper(PaperKind.INSURANCE)
    ReminderKind.PUC -> actions.onPaper(PaperKind.PUC)
    ReminderKind.RC -> actions.onPaper(PaperKind.RC)
    ReminderKind.CUSTOM -> actions.onReminder(customIdOf(due))
}

@DrawableRes
internal fun dueIcon(kind: ReminderKind): Int =
    when (kind) {
        ReminderKind.SERVICE -> HlIcons.Garage
        ReminderKind.CHAIN -> R.drawable.garage_ic_chain
        ReminderKind.INSURANCE -> R.drawable.garage_ic_shield
        ReminderKind.PUC -> R.drawable.garage_ic_leaf
        ReminderKind.RC -> R.drawable.garage_ic_paper
        ReminderKind.CUSTOM -> HlIcons.Bell
    }

/** "Log a service" when the card does not ask for it, the two latest entries, and all of them past two. */
@Composable
internal fun ServiceLog(
    state: GarageUiState,
    actions: GarageActions,
) {
    val entries = newestFirst(state.data.log)
    HlSection(stringResource(R.string.garage_service_log)) {
        val asks = serviceAsks(state)
        if (!asks) {
            GarageRow(
                HlIcons.Plus,
                stringResource(R.string.garage_log_service),
                detail = null,
                trailing = null,
                onClick = actions.onLogService,
                tone = RowTone.ADD,
            )
        }
        entries.take(LATEST_ENTRIES).forEachIndexed { index, entry ->
            if (index > 0 || !asks) HlGroupDivider()
            EntryRow(entry, state.today)
        }
        if (entries.size > LATEST_ENTRIES) {
            HlGroupDivider()
            GarageRow(
                icon = null,
                title = stringResource(R.string.garage_all_entries),
                detail = null,
                trailing = entries.size.toString(),
                onClick = actions.onAllEntries,
            )
        }
    }
}

/** Whether the next service card shows its brand "Log this service" (due soon, overdue, or not started). */
internal fun serviceAsks(state: GarageUiState): Boolean =
    state.status.service
        ?.state
        .let { it == null || it == DueState.SOON || it == DueState.OVERDUE }

/** The odometer, the colourway and the meter. */
@Composable
internal fun BikeSection(
    state: GarageUiState,
    actions: GarageActions,
) {
    val colors = HlTheme.colors
    val reading = state.status.latest
    HlSection(stringResource(R.string.garage_bike)) {
        GarageRow(
            R.drawable.garage_ic_odometer,
            stringResource(R.string.garage_odometer),
            odometerDetail(state.readingDaysAgo),
            reading?.let { km(it.km) } ?: stringResource(R.string.garage_add),
            onClick = actions.onOdometer,
        )
        HlGroupDivider()
        val colourway = Colourway.of(state.data.colourway)
        GarageRow(
            icon = null,
            title = stringResource(R.string.garage_colourway),
            detail = stringResource(R.string.garage_colourway_detail),
            trailing = stringResource(colourway.short),
            onClick = actions.onColourway,
            swatch = swatch(colourway),
        )
        HlGroupDivider()
        GarageRow(
            HlIcons.Bluetooth,
            stringResource(R.string.garage_meter),
            state.meter?.name,
            stringResource(if (state.meter != null) R.string.garage_linked else R.string.garage_not_linked),
            onClick = null,
            trailingColor = if (state.meter != null) colors.success else Color.Unspecified,
        )
    }
}

@Composable
private fun odometerDetail(daysAgo: Long?): String =
    when (daysAgo) {
        null -> stringResource(R.string.garage_odometer_read)
        0L -> stringResource(R.string.garage_odometer_today)
        1L -> stringResource(R.string.garage_odometer_yesterday)
        else -> pluralStringResource(R.plurals.garage_odometer_days, daysAgo.toInt(), daysAgo.toInt())
    }

/** A colourway's swatch (Paper "Garage 2", the Colourway row's disc: Matte Grey #5E615A). */
internal fun swatch(colourway: Colourway): Color =
    when (colourway) {
        Colourway.MATTE_GREY -> Color(MATTE_GREY_SWATCH)
    }

private const val LATEST_ENTRIES = 2

private const val MATTE_GREY_SWATCH = 0xFF5E615A

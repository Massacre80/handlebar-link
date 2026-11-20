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
import com.abid.handlebarlink.ui.settings.SubPage
import java.time.LocalDate

/** Every service log entry, newest first ("All entries"), as a page with Back. */
@Composable
fun ServiceLogScreen(
    state: GarageUiState,
    onBack: () -> Unit,
    onEntry: (Long) -> Unit,
) {
    SubPage(stringResource(R.string.garage_service_log), onBack) {
        HlGroup {
            newestFirst(state.data.log).forEachIndexed { index, entry ->
                if (index > 0) HlGroupDivider()
                EntryRow(entry, state.today) { onEntry(entry.id) }
            }
        }
    }
}

/** One log entry: what, at what km and where, on what day; a tap opens it to change or delete ([onClick]). */
@Composable
internal fun EntryRow(
    entry: ServiceEntry,
    today: LocalDate,
    onClick: () -> Unit,
) {
    val extra =
        listOfNotNull(entry.workshop, entry.cost?.let { stringResource(R.string.garage_cost, grouped(it)) })
            .joinToString(" · ")
    GarageRow(
        if (entry.kind == ServiceKind.CHAIN) R.drawable.garage_ic_chain else HlIcons.Garage,
        stringResource(serviceKindName(entry.kind)),
        if (extra.isEmpty()) km(entry.km) else stringResource(R.string.garage_entry_detail, grouped(entry.km), extra),
        shortDate(LocalDate.ofEpochDay(entry.day), today),
        onClick = onClick,
    )
}

private val NEWEST_FIRST = compareByDescending<ServiceEntry> { it.day }.thenByDescending { it.id }

/** Newest first, for the log page too. */
internal fun newestFirst(log: List<ServiceEntry>): List<ServiceEntry> = log.sortedWith(NEWEST_FIRST)

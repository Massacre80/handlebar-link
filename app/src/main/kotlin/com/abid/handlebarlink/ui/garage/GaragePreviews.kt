package com.abid.handlebarlink.ui.garage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlDialog
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.preview.Landscape
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.data.SelectedBike
import com.abid.handlebarlink.garage.ChainPlan
import com.abid.handlebarlink.garage.Colourway
import com.abid.handlebarlink.garage.GarageData
import com.abid.handlebarlink.garage.Lead
import com.abid.handlebarlink.garage.OdometerReading
import com.abid.handlebarlink.garage.Paper
import com.abid.handlebarlink.garage.PaperKind
import com.abid.handlebarlink.garage.ServiceEntry
import com.abid.handlebarlink.garage.ServiceKind
import com.abid.handlebarlink.garage.ServicePlan
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// The artboards' states (Paper "Garage 1–4", "2L", sheets 5–8) on the artboards' day, 20 Nov 2026, for previews and
// the screenshot tests (GarageScreenshots).

private val PreviewToday: LocalDate = LocalDate.parse("2026-11-20")
private val PreviewMeter = SelectedBike("AA:BB:CC:DD:EE:FF", "HONDA BTU 2040")
private val PreviewReading = OdometerReading(PREVIEW_ODOMETER, Instant.parse("2026-11-18T10:00:00Z").toEpochMilli())

private fun day(text: String): Long = LocalDate.parse(text).toEpochDay()

/** The log, ids in the order entered. */
private val PreviewLog =
    listOf(
        ServiceEntry(0, day("2026-09-02"), LAST_SERVICE_KM, ServiceKind.GENERAL, "Wings Honda", PREVIEW_COST),
        ServiceEntry(0, day("2026-08-11"), CHAIN_KM, ServiceKind.CHAIN, "Wings Honda"),
        ServiceEntry(0, day("2026-03-02"), SECOND_SERVICE_KM, ServiceKind.GENERAL),
        ServiceEntry(0, day("2026-01-14"), TYRES_KM, ServiceKind.TYRES),
        ServiceEntry(0, day("2025-09-02"), FIRST_SERVICE_KM, ServiceKind.GENERAL),
        ServiceEntry(0, day("2025-08-01"), DELIVERY_KM, ServiceKind.OIL),
    ).mapIndexed { index, entry -> entry.copy(id = index + 1L) }

/** Garage 2: everything on track. */
private val AllGoodData =
    GarageData(
        odometer = listOf(PreviewReading),
        log = PreviewLog,
        service = ServicePlan(startDay = day("2026-09-02"), startKm = LAST_SERVICE_KM),
        chain = ChainPlan(startKm = CHAIN_START_KM),
        papers =
            mapOf(
                PaperKind.INSURANCE to Paper(day("2027-03-14"), Lead.MONTH),
                PaperKind.PUC to Paper(day("2027-08-03"), Lead.TWO_WEEKS),
                PaperKind.RC to Paper(day("2039-06-30"), Lead.MONTH),
            ),
        colourway = Colourway.MATTE_GREY.name,
        nextId = PreviewLog.size + 1L,
    )

private fun preview(data: GarageData) = garageUiState(data, PreviewMeter, PreviewToday, ZoneOffset.UTC)

/** Garage 1: nothing added yet. */
internal val PreviewGarageEmpty = preview(GarageData())

/** Garage 2: all on track. */
internal val PreviewGarageAllGood = preview(AllGoodData)

/** Garage 3: the service due in 9 days or 610 km. */
internal val PreviewGarageServiceSoon =
    preview(AllGoodData.copy(service = ServicePlan(startDay = day("2026-05-29"), startKm = SOON_START_KM)))

/** Garage 4: the PUC ran out on 16 Nov. */
internal val PreviewGaragePucOverdue =
    preview(AllGoodData.copy(papers = AllGoodData.papers + (PaperKind.PUC to Paper(day("2026-11-16"), Lead.TWO_WEEKS))))

/** Papers added, no service logged yet: the card asks for the last service. */
internal val PreviewGarageNoService = preview(GarageData(papers = AllGoodData.papers))

/** A Garage page in [variant] and [layout] (upright by default), on the app's ground. */
@Composable
internal fun GarageFrame(
    variant: HlThemeVariant,
    layout: WindowLayout = WindowLayout.COLUMN,
    content: @Composable () -> Unit,
) {
    HlTheme(variant) {
        CompositionLocalProvider(LocalWindowLayout provides layout) {
            HlBackground(Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun TabPreview(
    state: GarageUiState,
    variant: HlThemeVariant = HlThemeVariant.NIGHT,
) = GarageFrame(variant) { GarageScreen(state, GarageActions(), PaddingValues()) }

@Preview(name = "Garage 1 · nothing added", widthDp = 411, heightDp = 1240)
@Composable
private fun GarageEmptyPreview() = TabPreview(PreviewGarageEmpty)

@Preview(name = "Garage 2 · all good", widthDp = 411, heightDp = 1720)
@Composable
private fun GarageAllGoodPreview() = TabPreview(PreviewGarageAllGood)

@Preview(name = "Garage 3 · service soon", widthDp = 411, heightDp = 1720)
@Composable
private fun GarageServiceSoonPreview() = TabPreview(PreviewGarageServiceSoon)

@Preview(name = "Garage 4 · PUC overdue", widthDp = 411, heightDp = 1800)
@Composable
private fun GaragePucOverduePreview() = TabPreview(PreviewGaragePucOverdue)

@Preview(name = "Garage · no service yet", widthDp = 411, heightDp = 1400)
@Composable
private fun GarageNoServicePreview() = TabPreview(PreviewGarageNoService)

@Landscape
@Composable
internal fun GarageLandscapePreview() =
    GarageFrame(HlThemeVariant.NIGHT, WindowLayout.PANES) {
        GarageScreen(PreviewGarageAllGood, GarageActions(), PaddingValues())
    }

// The artboards' numbers: the odometer 15,860 km two days ago, the last general service at 14,100 km on 2 Sep, the
// chain cleaned at 15,200 (1,000 km interval: due in 340 km), Garage 3's start 10,470 (due at 16,470: 610 km left).
private const val PREVIEW_ODOMETER = 15_860
private const val LAST_SERVICE_KM = 14_100
private const val CHAIN_KM = 13_420
private const val CHAIN_START_KM = 15_200
private const val SOON_START_KM = 10_470
private const val SECOND_SERVICE_KM = 8_150
private const val TYRES_KM = 7_300
private const val FIRST_SERVICE_KM = 1_020
private const val DELIVERY_KM = 12
private const val PREVIEW_COST = 2_450

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

// The sheets and dialogs of the Garage tab (Paper "Garage 5–8"), over the tab's ground, on the artboards' day.

/** A sheet as it sits over the tab, at the bottom; previews are tall, as the preview tool draws a keyboard. */
@Composable
internal fun SheetFrame(content: @Composable () -> Unit) {
    GarageFrame(HlThemeVariant.NIGHT) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { content() }
    }
}

@Preview(name = "Garage 5 · log a service", widthDp = 411, heightDp = 1200)
@Composable
private fun LogServicePreview() =
    SheetFrame {
        val state = PreviewGarageAllGood
        LogServiceSheet(state.today, state.data.service, state.status.latest?.km, onSave = {}, onCancel = {})
    }

@Preview(name = "Garage 6 · insurance", widthDp = 411, heightDp = 1200)
@Composable
private fun InsurancePreview() =
    SheetFrame {
        val state = PreviewGarageAllGood
        PaperSheet(PaperKind.INSURANCE, state.data.papers[PaperKind.INSURANCE], state.today, { _, _ -> }, {})
    }

@Preview(name = "Garage 7 · forget this bike", widthDp = 411, heightDp = 1200)
@Composable
private fun ForgetPreview() =
    SheetFrame {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            HlDialog(
                HlIcons.Trash,
                stringResource(R.string.garage_forget_title),
                stringResource(R.string.garage_forget_text),
                stringResource(R.string.garage_forget_confirm),
                {},
                stringResource(R.string.cancel),
                {},
                destructive = true,
            )
        }
    }

@Preview(name = "Garage 8 · colourway", widthDp = 411, heightDp = 1200)
@Composable
private fun ColourwayPreview() = SheetFrame { ColourwaySheet(Colourway.MATTE_GREY, {}, {}) }

@Preview(name = "Garage · own reminder", widthDp = 411, heightDp = 1200)
@Composable
private fun ReminderPreview() = SheetFrame { ReminderSheet(null, PreviewGarageAllGood.today, { _, _, _ -> }, null, {}) }

@Preview(name = "Garage · odometer", widthDp = 411, heightDp = 1200)
@Composable
private fun OdometerPreview() = SheetFrame { OdometerSheet(PreviewGarageAllGood.status.latest?.km, {}, {}) }

@Preview(name = "Garage · chain", widthDp = 411, heightDp = 1200)
@Composable
private fun ChainPreview() =
    SheetFrame {
        ChainSheet(PreviewGarageAllGood.data.chain, PreviewGarageAllGood.status.latest?.km, { _, _ -> }, {})
    }

@Preview(name = "Garage · service interval", widthDp = 411, heightDp = 1200)
@Composable
private fun IntervalPreview() = SheetFrame { ServiceIntervalSheet(PreviewGarageAllGood.data.service, { _, _ -> }, {}) }

@Preview(name = "Garage · all entries", widthDp = 411, heightDp = 1200)
@Composable
private fun ServiceLogPreview() = GarageFrame(HlThemeVariant.NIGHT) { ServiceLogScreen(PreviewGarageAllGood, {}, {}) }

package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

/** The instrument card's nine states, as Paper "BikePlay card" draws them ("B · 1" … "B · 9"). */
internal enum class InstrumentSample {
    CONNECTED,
    STARTING,
    CONNECTING,
    NOT_SET_UP,
    WIFI_OFF,
    BLUETOOTH_OFF,
    PERMISSION,
    RETRYING,
    OFF,
}

/** Every [InstrumentSample], for previews and screenshot tests. */
internal class InstrumentSamples : PreviewParameterProvider<InstrumentSample> {
    override val values: Sequence<InstrumentSample> = InstrumentSample.entries.asSequence()
}

/** [sample] with Paper's words. */
@Composable
internal fun InstrumentState(sample: InstrumentSample) {
    when (sample) {
        InstrumentSample.CONNECTED -> Connected()
        InstrumentSample.STARTING -> Card("Starting", "Calling your iPhone", MeterValue.Progress(STEP_1))
        InstrumentSample.CONNECTING -> Card("Connecting", "Waiting for the iPhone to join", MeterValue.Progress(STEP_3))
        InstrumentSample.NOT_SET_UP -> NotSetUp()
        InstrumentSample.WIFI_OFF -> NeedsYou("Wi-Fi is off", "Turn on Wi-Fi")
        InstrumentSample.BLUETOOTH_OFF -> NeedsYou("Bluetooth is off", "Turn on Bluetooth")
        InstrumentSample.PERMISSION -> NeedsYou("Allow Nearby devices", "Allow")
        InstrumentSample.RETRYING -> Retrying()
        InstrumentSample.OFF -> Off()
    }
}

@Composable
private fun Card(
    state: String,
    detail: String,
    value: MeterValue,
    tone: MeterTone = MeterTone.GOOD,
    action: InstrumentAction? = null,
) = HlInstrumentCard("BikePlay", state, detail, value, tone, onClick = {}, action = action)

@Composable
private fun Connected() =
    HlInstrumentCard(
        "BikePlay",
        "Connected",
        "AbidiPhone",
        MeterValue.Segments(MeterValue.SEGMENTS),
        MeterTone.GOOD,
        onClick = {},
        start = InstrumentReading("Strong", "Link"),
        end = InstrumentReading("5 GHz", "Ch 36"),
        action = InstrumentAction("Open BikePlay", primary = true, icon = HlIcons.Play) {},
    )

@Composable
private fun NotSetUp() =
    Card(
        "Not set up",
        "BikePlay shows your iPhone’s CarPlay on the bike. Pair it once to start.",
        MeterValue.Empty,
        action = InstrumentAction("Add iPhone", primary = true, icon = HlIcons.PlusBold) {},
    )

@Composable
private fun Retrying() =
    Card(
        "Couldn’t connect",
        "iPhone didn’t answer · Retrying in 8 s",
        MeterValue.Progress(DRAINED, draining = true),
        MeterTone.DANGER,
        InstrumentAction("Try now", primary = true) {},
    )

@Composable
private fun Off() =
    Card(
        "BikePlay off",
        "Starts again when you ride",
        MeterValue.Empty,
        action = InstrumentAction("Start BikePlay", primary = true, icon = HlIcons.Play) {},
    )

@Composable
private fun NeedsYou(
    reason: String,
    fix: String,
) = HlInstrumentCard(
    "BikePlay",
    "Needs you",
    reason,
    MeterValue.Full,
    MeterTone.DANGER,
    onClick = {},
    action = InstrumentAction(fix, primary = true) {},
)

@Preview(name = "Instrument · Night", widthDp = 411)
@Preview(name = "Instrument · Night · large text", widthDp = 411, fontScale = 1.5f)
@Composable
private fun InstrumentNightPreview(
    @PreviewParameter(InstrumentSamples::class) sample: InstrumentSample,
) = Gallery(HlThemeVariant.NIGHT) { InstrumentState(sample) }

@Preview(name = "Instrument · Bento", widthDp = 411)
@Composable
private fun InstrumentBentoPreview(
    @PreviewParameter(InstrumentSamples::class) sample: InstrumentSample,
) = Gallery(HlThemeVariant.BENTO) { InstrumentState(sample) }

/** Paper's step 1 and step 3 of 4, and the retry's arc with 55 % of its pause left ("B · 8"). */
private const val STEP_1 = 0.25f
private const val STEP_3 = 0.75f
private const val DRAINED = 0.55f

package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.abid.handlebarlink.core.designsystem.preview.FontScales
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.android.tools.screenshot.PreviewTest

// Every component group in both looks, at font scale 1 and 1.5 (testing-setup skill, Step 8).

@PreviewTest
@FontScales
@Composable
fun PillsNight() = Gallery(HlThemeVariant.NIGHT) { Pills() }

@PreviewTest
@FontScales
@Composable
fun PillsBento() = Gallery(HlThemeVariant.BENTO) { Pills() }

@PreviewTest
@FontScales
@Composable
fun GaugesNight() = Gallery(HlThemeVariant.NIGHT) { Gauges() }

@PreviewTest
@FontScales
@Composable
fun GaugesBento() = Gallery(HlThemeVariant.BENTO) { Gauges() }

@PreviewTest
@FontScales
@Composable
fun DevicesNight() = Gallery(HlThemeVariant.NIGHT) { Devices() }

@PreviewTest
@FontScales
@Composable
fun DevicesBento() = Gallery(HlThemeVariant.BENTO) { Devices() }

@PreviewTest
@FontScales
@Composable
fun CarPlayCardNight() = Gallery(HlThemeVariant.NIGHT) { CarPlayStates() }

@PreviewTest
@FontScales
@Composable
fun CarPlayCardBento() = Gallery(HlThemeVariant.BENTO) { CarPlayStates() }

@PreviewTest
@FontScales
@Composable
fun InstrumentCardNight(
    @PreviewParameter(InstrumentSamples::class) sample: InstrumentSample,
) = Gallery(HlThemeVariant.NIGHT) { InstrumentState(sample) }

@PreviewTest
@FontScales
@Composable
fun InstrumentCardBento(
    @PreviewParameter(InstrumentSamples::class) sample: InstrumentSample,
) = Gallery(HlThemeVariant.BENTO) { InstrumentState(sample) }

@PreviewTest
@FontScales
@Composable
fun TabBarNight() = Gallery(HlThemeVariant.NIGHT) { HlTabBar(PreviewTabs, selected = 0, onSelect = {}) }

@PreviewTest
@FontScales
@Composable
fun TabBarBento() = Gallery(HlThemeVariant.BENTO) { HlTabBar(PreviewTabs, selected = 0, onSelect = {}) }

@PreviewTest
@FontScales
@Composable
fun TabRailNight() = Gallery(HlThemeVariant.NIGHT) { Rail() }

@PreviewTest
@FontScales
@Composable
fun TabRailBento() = Gallery(HlThemeVariant.BENTO) { Rail() }

@PreviewTest
@FontScales
@Composable
fun SmartButtonsNight() = Gallery(HlThemeVariant.NIGHT) { SmartButtons() }

@PreviewTest
@FontScales
@Composable
fun SmartButtonsBento() = Gallery(HlThemeVariant.BENTO) { SmartButtons() }

@PreviewTest
@FontScales
@Composable
fun SwitchesNight() = Gallery(HlThemeVariant.NIGHT) { Switches() }

@PreviewTest
@FontScales
@Composable
fun SwitchesBento() = Gallery(HlThemeVariant.BENTO) { Switches() }

@PreviewTest
@FontScales
@Composable
fun ButtonsNight() = Gallery(HlThemeVariant.NIGHT) { Buttons() }

@PreviewTest
@FontScales
@Composable
fun ButtonsBento() = Gallery(HlThemeVariant.BENTO) { Buttons() }

@PreviewTest
@FontScales
@Composable
fun SheetNight() = HlTheme(HlThemeVariant.NIGHT) { Sheet() }

@PreviewTest
@FontScales
@Composable
fun SheetBento() = HlTheme(HlThemeVariant.BENTO) { Sheet() }

@PreviewTest
@FontScales
@Composable
fun AppHeaderNight() = Gallery(HlThemeVariant.NIGHT) { AppHeaders() }

@PreviewTest
@FontScales
@Composable
fun AppHeaderBento() = Gallery(HlThemeVariant.BENTO) { AppHeaders() }

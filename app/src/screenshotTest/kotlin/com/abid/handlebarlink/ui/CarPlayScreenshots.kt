package com.abid.handlebarlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.preview.LargeText
import com.abid.handlebarlink.core.designsystem.preview.ScreenSizes
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.carplay.CarPlayScreenSheet
import com.abid.handlebarlink.ui.carplay.IphoneSheet
import com.abid.handlebarlink.ui.carplay.PairIphoneScreen
import com.abid.handlebarlink.ui.carplay.PairIphoneUiState
import com.abid.handlebarlink.ui.carplay.PairProblem
import com.abid.handlebarlink.ui.carplay.PreviewIphoneSheet
import com.abid.handlebarlink.ui.carplay.PreviewPairActions
import com.abid.handlebarlink.ui.carplay.PreviewPairCode
import com.abid.handlebarlink.ui.carplay.PreviewPairPaired
import com.abid.handlebarlink.ui.carplay.PreviewPairSteps
import com.abid.handlebarlink.ui.carplay.PreviewPairWaiting
import com.android.tools.screenshot.PreviewTest

// In-app iPhone pairing (Paper "CarPlay — flow v2", iPhone 1–3) and the iPhone sheet (Sheet 6): both looks at the
// artboard's size and with large text; the first step also at the nine sizes.

@Composable
private fun Pair(
    variant: HlThemeVariant,
    state: PairIphoneUiState,
) = HlTheme(variant) {
    HlBackground(Modifier.fillMaxSize()) { PairIphoneScreen(state, "CB350RS", PreviewPairActions) }
}

@PreviewTest
@Artboard
@ScreenSizes
@LargeText
@Composable
fun PairStepsNight() = Pair(HlThemeVariant.NIGHT, PreviewPairSteps)

@PreviewTest
@Artboard
@Composable
fun PairStepsBento() = Pair(HlThemeVariant.BENTO, PreviewPairSteps)

@PreviewTest
@Artboard
@LargeText
@Composable
fun PairWaitingNight() = Pair(HlThemeVariant.NIGHT, PreviewPairWaiting)

@PreviewTest
@Artboard
@Composable
fun PairWaitingBento() = Pair(HlThemeVariant.BENTO, PreviewPairWaiting)

@PreviewTest
@Artboard
@Composable
fun PairWaitingFailedNight() =
    Pair(HlThemeVariant.NIGHT, PreviewPairWaiting.copy(problem = PairProblem.FAILED, otherName = "Abid's iPhone"))

@PreviewTest
@Artboard
@LargeText
@Composable
fun PairCodeNight() = Pair(HlThemeVariant.NIGHT, PreviewPairCode)

@PreviewTest
@Artboard
@Composable
fun PairCodeBento() = Pair(HlThemeVariant.BENTO, PreviewPairCode)

@PreviewTest
@Artboard
@LargeText
@Composable
fun PairPairedNight() = Pair(HlThemeVariant.NIGHT, PreviewPairPaired)

@PreviewTest
@Artboard
@Composable
fun PairPairedBento() = Pair(HlThemeVariant.BENTO, PreviewPairPaired)

@Composable
private fun Sheet(variant: HlThemeVariant) =
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.BottomCenter)) { IphoneSheet(PreviewIphoneSheet, {}, {}, {}) }
        }
    }

@PreviewTest
@Artboard
@LargeText
@Composable
fun IphoneSheetNight() = Sheet(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@Composable
fun IphoneSheetBento() = Sheet(HlThemeVariant.BENTO)

@Composable
private fun ScreenSheet(variant: HlThemeVariant) =
    HlTheme(variant) {
        HlBackground(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.BottomCenter)) { CarPlayScreenSheet(CarPlayOrientation.LANDSCAPE, {}, {}) }
        }
    }

@PreviewTest
@Artboard
@LargeText
@Composable
fun CarPlayScreenSheetNight() = ScreenSheet(HlThemeVariant.NIGHT)

@PreviewTest
@Artboard
@Composable
fun CarPlayScreenSheetBento() = ScreenSheet(HlThemeVariant.BENTO)

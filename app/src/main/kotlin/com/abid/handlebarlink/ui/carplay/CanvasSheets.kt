package com.abid.handlebarlink.ui.carplay

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlHeader
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlSheet
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant

// The CarPlay tab's "CarPlay screen" picker (landscape or portrait), laid out as the Handlebar picker
// (RiderActionSheet: header, option rows with the chosen one in brand, a footnote, Cancel).

/** The "CarPlay screen" sheet: landscape (the default) or portrait, and that a running CarPlay reconnects. */
@Composable
fun CarPlayScreenSheet(
    chosen: CarPlayOrientation,
    onChoose: (CarPlayOrientation) -> Unit,
    onCancel: () -> Unit,
) {
    CanvasSheet(
        orientationIcon(chosen),
        R.string.carplay_screen_row,
        R.string.carplay_screen_sheet_detail,
        CarPlayOrientation.entries.map {
            CanvasOption(it, orientationIcon(it), orientationName(it), orientationDetail(it))
        },
        chosen,
        onChoose,
        onCancel,
    )
}

/** One choice of a canvas sheet ([icon] null: words only). */
private class CanvasOption<T>(
    val value: T,
    @param:DrawableRes val icon: Int?,
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
)

@Composable
private fun <T> CanvasSheet(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes detail: Int,
    options: List<CanvasOption<T>>,
    chosen: T,
    onChoose: (T) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = HlTheme.spacing
    HlSheet(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(spacing.s20),
        ) {
            HlHeader(icon, stringResource(title), stringResource(detail), brand = true)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                options.forEach { option ->
                    HlOptionRow(
                        option.icon,
                        stringResource(option.title),
                        stringResource(option.detail),
                        selected = option.value == chosen,
                        onClick = { onChoose(option.value) },
                        accent = true,
                    )
                }
            }
            HlText(
                stringResource(R.string.carplay_canvas_footnote),
                HlTheme.typography.label.copy(fontWeight = FontWeight.Normal),
                HlTheme.colors.inkMuted,
                Modifier.padding(horizontal = spacing.s4),
            )
            HlSecondaryButton(stringResource(R.string.cancel), onCancel)
        }
    }
}

/** The phone the way the picture stands. */
@DrawableRes
internal fun orientationIcon(orientation: CarPlayOrientation): Int =
    when (orientation) {
        CarPlayOrientation.LANDSCAPE -> HlIcons.PhoneLandscape
        CarPlayOrientation.PORTRAIT -> HlIcons.Phone
    }

/** "Landscape" or "Portrait". */
@StringRes
internal fun orientationName(orientation: CarPlayOrientation): Int =
    when (orientation) {
        CarPlayOrientation.LANDSCAPE -> R.string.carplay_screen_landscape
        CarPlayOrientation.PORTRAIT -> R.string.carplay_screen_portrait
    }

@StringRes
private fun orientationDetail(orientation: CarPlayOrientation): Int =
    when (orientation) {
        CarPlayOrientation.LANDSCAPE -> R.string.carplay_screen_landscape_detail
        CarPlayOrientation.PORTRAIT -> R.string.carplay_screen_portrait_detail
    }

@Preview(name = "CarPlay screen sheet · Night", widthDp = 411, heightDp = 856)
@Composable
private fun CarPlayScreenSheetPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { CarPlayScreenSheet(CarPlayOrientation.LANDSCAPE, {}, {}) }
    }
}

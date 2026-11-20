package com.abid.handlebarlink.ui.garage

import android.app.DatePickerDialog
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlHeader
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlSectionLabel
import com.abid.handlebarlink.core.designsystem.component.HlSheet
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import java.time.LocalDate

// The parts Garage's sheets share (Paper "Garage 5", "Garage 6"): the frame, a labelled field with its help line, the
// big number and date fields, and Save over Cancel.

/**
 * A Garage sheet: the brand header ([icon], [title], [subtitle]) and the [content] groups, 14 dp apart (owner,
 * 2026-11-20: sheets space their groups evenly by 14 dp; polish branch's token at the merge), scrolling when tall,
 * clear of the navigation bar and the keyboard.
 */
@Composable
internal fun GarageSheet(
    icon: Int,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    HlSheet(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            // Above the keyboard too: the sheet grows up from the bottom, so Save stays reachable while typing.
            Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s14),
        ) {
            HlHeader(icon, title, subtitle, brand = true)
            content()
        }
    }
}

/** A field's group: its upper-case [label], the field, and an optional [help] line under it, 8 dp apart. */
@Composable
internal fun Labelled(
    label: String,
    help: String? = null,
    field: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        HlSectionLabel(label.uppercase())
        field()
        help?.let {
            HlText(
                it,
                HlTheme.typography.label,
                HlTheme.colors.inkMuted,
                Modifier.padding(horizontal = HlTheme.spacing.s4),
            )
        }
    }
}

/** The main action and Cancel, 8 dp apart; Save is disabled until the sheet's input is complete ([canSave]). */
@Composable
internal fun SaveCancel(
    save: String,
    canSave: Boolean,
    onSave: () -> Unit,
    cancel: String,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s8)) {
        HlButton(save, onSave, enabled = canSave, brand = true)
        HlSecondaryButton(cancel, onCancel)
    }
}

/**
 * A number in the design's big field (Paper "Garage 5", Odometer: 60 dp, the figure, the [unit] at the end): digits
 * only, up to [maxDigits]; a brand edge while focused.
 */
@Composable
internal fun NumberField(
    state: TextFieldState,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    maxDigits: Int = MAX_KM_DIGITS,
) {
    val colors = HlTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(HlTheme.radii.control)
    BasicTextField(
        state = state,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        inputTransformation = DigitsOnly.then(InputTransformation.maxLength(maxDigits)),
        textStyle = HlTheme.typography.figure.copy(color = colors.ink),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        cursorBrush = SolidColor(colors.brand),
        interactionSource = interaction,
        decorator = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = FIELD_DP.dp)
                    .background(colors.well, shape)
                    .border(
                        if (focused) HlTheme.strokes.outline else HlTheme.strokes.hairline,
                        if (focused) colors.brand else colors.line,
                        shape,
                    ).padding(horizontal = HlTheme.spacing.s18),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s16),
            ) {
                Box(Modifier.weight(1f)) { field() }
                HlText(
                    unit,
                    HlTheme.typography.value.copy(fontWeight = FontWeight.Medium),
                    colors.inkMuted,
                    maxLines = 1,
                )
            }
        },
    )
}

/** A date in the big field (Paper "Garage 6", Runs out on): the date, or the hint, and the brand "Change". */
@Composable
internal fun DateField(
    text: String?,
    hint: String,
    change: String,
    onClick: () -> Unit,
) {
    val colors = HlTheme.colors
    val shape = RoundedCornerShape(HlTheme.radii.control)
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FIELD_DP.dp)
            .background(colors.well, shape)
            .border(HlTheme.strokes.hairline, colors.line, shape)
            .clip(shape)
            .clickable(press, LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = HlTheme.spacing.s18),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s16),
    ) {
        HlText(
            text ?: hint,
            if (text != null) HlTheme.typography.figure else HlTheme.typography.value,
            if (text != null) colors.ink else colors.inkMuted,
            Modifier.weight(1f),
            maxLines = 1,
        )
        HlText(change, HlTheme.typography.value, colors.brand, maxLines = 1)
    }
}

/**
 * Opens the platform's date picker on [initial] and hands the chosen day to [onPick]. Android's own dialog (no Material
 * in this app); a picker in the app's design is not drawn yet (docs/PLAN.md "Garage").
 */
@Composable
internal fun rememberDatePicker(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val pick by rememberUpdatedState(onPick)
    val start by rememberUpdatedState(initial)
    return remember(context) {
        {
            DatePickerDialog(
                context,
                { _, year, month, day -> pick(LocalDate.of(year, month + 1, day)) },
                start.year,
                start.monthValue - 1,
                start.dayOfMonth,
            ).show()
        }
    }
}

/** The field's text as a whole number; null when empty. */
internal fun TextFieldState.number(): Int? = text.toString().toIntOrNull()

/** Refuses anything but digits. */
private object DigitsOnly : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (!asCharSequence().all(Char::isDigit)) revertAllChanges()
    }
}

/** Paper "Garage 5": the odometer field is 60 dp high. */
private const val FIELD_DP = 60

/** Up to 999,999 km. */
internal const val MAX_KM_DIGITS = 6

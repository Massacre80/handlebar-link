package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.abid.handlebarlink.core.designsystem.theme.HlTheme

/**
 * A one-line text field in the shape of [HlValueRow] (the sheet's "Name" field, Paper "Flow · Add device"):
 * the text on the left, a [hint] on the right; [placeholder] shows while it is empty.
 */
@Composable
public fun HlTextField(
    state: TextFieldState,
    hint: String?,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    container: Color = HlTheme.colors.raisedLo,
) {
    val colors = HlTheme.colors
    val style = HlTheme.typography.value.copy(fontWeight = FontWeight.Medium)
    BasicTextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        textStyle = style.copy(color = colors.ink),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        cursorBrush = SolidColor(colors.ink),
        decorator = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = HlTheme.sizes.controlLarge)
                    .clip(rowShape(HlTheme.radii.control))
                    .background(container)
                    .padding(horizontal = HlTheme.spacing.s16, vertical = HlTheme.spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12),
            ) {
                Box(Modifier.weight(1f)) {
                    if (state.text.isEmpty() &&
                        placeholder != null
                    ) {
                        HlText(placeholder, style, colors.inkMuted, maxLines = 1)
                    }
                    field()
                }
                hint?.let { HlText(it, HlTheme.typography.meta, colors.inkMuted, maxLines = 1) }
            }
        },
    )
}

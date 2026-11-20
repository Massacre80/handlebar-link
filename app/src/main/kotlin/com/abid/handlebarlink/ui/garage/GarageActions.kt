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

/** What the rider can do on the Garage tab; each opens a sheet, a dialog or a page. */
data class GarageActions(
    val onLogService: () -> Unit = {},
    val onOdometer: () -> Unit = {},
    val onServiceInterval: () -> Unit = {},
    val onChain: () -> Unit = {},
    val onPaper: (PaperKind) -> Unit = {},
    val onReminder: (Long?) -> Unit = {},
    val onColourway: () -> Unit = {},
    val onAllEntries: () -> Unit = {},
    val onEntry: (Long) -> Unit = {},
    val onForget: () -> Unit = {},
)

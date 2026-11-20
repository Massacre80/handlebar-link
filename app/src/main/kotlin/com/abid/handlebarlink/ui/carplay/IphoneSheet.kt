package com.abid.handlebarlink.ui.carplay

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.IphonePairing
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.usedIphone
import com.abid.handlebarlink.core.designsystem.component.HlBackground
import com.abid.handlebarlink.core.designsystem.component.HlHeader
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlOptionRow
import com.abid.handlebarlink.core.designsystem.component.HlSecondaryButton
import com.abid.handlebarlink.core.designsystem.component.HlSheet
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One paired iPhone in the sheet; [used] is the one CarPlay connects to. */
@Immutable
data class SheetIphone(
    val iphone: PairedIphone,
    val used: Boolean,
    @param:StringRes val status: Int,
)

/** The iPhone sheet: the paired iPhones and the one CarPlay uses ([used], which "Forget" names). */
@Immutable
data class IphoneSheetUiState(
    val iphones: List<SheetIphone> = emptyList(),
) {
    /** The iPhone CarPlay uses, if any. */
    val used: PairedIphone? get() = iphones.firstOrNull { it.used }?.iphone
}

/** The sheet for the paired [iphones], the [chosen] address and CarPlay's [status]. */
internal fun iphoneSheet(
    iphones: List<PairedIphone>,
    chosen: String?,
    status: CarPlayStatus,
): IphoneSheetUiState {
    val used = usedIphone(iphones, chosen)
    val connected = status.isConnected()
    return IphoneSheetUiState(
        iphones.map { iphone ->
            val isUsed = iphone == used
            val text =
                when {
                    isUsed && connected -> R.string.carplay_iphone_connected
                    isUsed -> R.string.sheet_iphone_used
                    else -> R.string.carplay_iphone_paired
                }
            SheetIphone(iphone, isUsed, text)
        },
    )
}

/** The iPhone sheet's view model: choose another iPhone, forget one (only from the dialog, [forgetNow]). */
@HiltViewModel
class IphoneSheetViewModel
    @Inject
    constructor(
        private val pairing: IphonePairing,
        coordinator: CarPlayCoordinator,
    ) : ViewModel() {
        /** The sheet's state. */
        val uiState: StateFlow<IphoneSheetUiState> =
            combine(pairing.iphones().filterNotNull(), pairing.chosen(), coordinator.status, ::iphoneSheet)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), IphoneSheetUiState())

        /** CarPlay uses [address] from now on. */
        fun choose(address: String) {
            viewModelScope.launch { pairing.choose(address) }
        }

        /** Forgets [address]; returns once stored (the dialog then closes). */
        suspend fun forgetNow(address: String) {
            pairing.forget(address)
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/**
 * Sheet 6, "iPhone" (Paper "CarPlay — flow v2", from the iPhone row): the paired iPhones, the one CarPlay uses with a
 * check and its status (tapping another chooses it), "Add another iPhone", and "Forget <name>" as a ghost button that
 * only asks ([onForget] opens the confirmation; owner rule: remove = HlSecondaryButton + HlDialog).
 */
@Composable
fun IphoneSheet(
    state: IphoneSheetUiState,
    onChoose: (PairedIphone) -> Unit,
    onAdd: () -> Unit,
    onForget: (PairedIphone) -> Unit,
) {
    val spacing = HlTheme.spacing
    HlSheet(Modifier.verticalScroll(rememberScrollState())) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
        ) {
            HlHeader(
                HlIcons.Phone,
                stringResource(R.string.sheet_iphone_title),
                stringResource(R.string.sheet_iphone_detail),
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.s8)) {
                state.iphones.forEach { row ->
                    HlOptionRow(
                        HlIcons.Phone,
                        row.iphone.name,
                        stringResource(row.status),
                        selected = row.used,
                        onClick = { onChoose(row.iphone) },
                        accent = true,
                    )
                }
                HlValueRow(
                    value = stringResource(R.string.sheet_iphone_add),
                    hint = null,
                    detail = stringResource(R.string.sheet_iphone_add_detail),
                    onClick = onAdd,
                    chevron = true,
                )
            }
            state.used?.let { used ->
                HlSecondaryButton(stringResource(R.string.sheet_iphone_forget, used.name), { onForget(used) })
            }
        }
    }
}

internal val PreviewIphoneSheet =
    IphoneSheetUiState(
        listOf(
            SheetIphone(PreviewIphone, used = true, R.string.carplay_iphone_connected),
            SheetIphone(PairedIphone("20:00:00:00:00:02", "iPhone SE"), used = false, R.string.carplay_iphone_paired),
        ),
    )

@Preview(widthDp = 411)
@Composable
private fun IphoneSheetPreview() {
    HlTheme(HlThemeVariant.NIGHT) {
        HlBackground(Modifier.fillMaxSize()) { IphoneSheet(PreviewIphoneSheet, {}, {}, {}) }
    }
}

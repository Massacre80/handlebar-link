package com.abid.handlebarlink.ui.carplay

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlInstrumentCard
import com.abid.handlebarlink.core.designsystem.component.InstrumentAction
import com.abid.handlebarlink.core.designsystem.component.InstrumentReading

/**
 * Home's BikePlay card ([HlInstrumentCard]): a tap outside the button opens the BikePlay tab ([onOpenTab]); the button
 * does what the tab's does ([onAction]).
 */
@Composable
fun BikePlayCard(
    state: BikePlayCardState,
    onAction: (TabAction) -> Unit,
    onOpenTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HlInstrumentCard(
        title = stringResource(R.string.carplay_title),
        state = stringResource(state.word),
        detail = state.detail.words(),
        value = state.meter,
        tone = state.tone,
        modifier = modifier,
        onClick = onOpenTab,
        start =
            if (state.phase == CardPhase.CONNECTED) {
                InstrumentReading(
                    state.quality?.let { stringResource(it.words) } ?: stringResource(R.string.no_value),
                    stringResource(R.string.carplay_link_row),
                )
            } else {
                null
            },
        end = if (state.phase == CardPhase.CONNECTED) bandReading(state.channel) else null,
        action =
            state.action?.let { action ->
                InstrumentAction(
                    stringResource(actionLabel(action)),
                    primaryAction(action),
                    icon =
                        when (action) {
                            TabAction.OPEN_CARPLAY, TabAction.START_CARPLAY -> HlIcons.Play
                            TabAction.ADD_IPHONE -> HlIcons.PlusBold
                            else -> null
                        },
                ) { onAction(action) }
            },
    )
}

/** "5 GHz" over "Ch 36"; without a channel "—" over "Wi-Fi". */
@Composable
private fun bandReading(channel: Int?): InstrumentReading =
    if (channel == null) {
        InstrumentReading(stringResource(R.string.no_value), stringResource(R.string.bikeplay_card_wifi))
    } else {
        InstrumentReading(
            stringResource(
                if (channel <=
                    LAST_2_4_GHZ
                ) {
                    R.string.bikeplay_card_band_2_4
                } else {
                    R.string.bikeplay_card_band_5
                },
            ),
            stringResource(R.string.bikeplay_card_channel, channel),
        )
    }

/** Channels 1-14 are 2.4 GHz (as the Link row). */
private const val LAST_2_4_GHZ = 14

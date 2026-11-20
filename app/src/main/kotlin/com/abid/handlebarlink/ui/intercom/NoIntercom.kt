package com.abid.handlebarlink.ui.intercom

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.designsystem.component.HlButton
import com.abid.handlebarlink.core.designsystem.component.HlGroup
import com.abid.handlebarlink.core.designsystem.component.HlGroupDivider
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.HlValueRow
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.devices.ADD_DEVICE_FROM_DEVICES
import com.abid.handlebarlink.ui.frame.HeroColumn
import com.abid.handlebarlink.ui.frame.HeroPanes
import com.abid.handlebarlink.ui.frame.HeroState
import com.abid.handlebarlink.ui.frame.LocalWindowLayout
import com.abid.handlebarlink.ui.frame.WindowLayout
import com.abid.handlebarlink.ui.navigation.ContainerTransform
import com.abid.handlebarlink.ui.navigation.containerContent
import com.abid.handlebarlink.ui.navigation.containerTransform
import com.abid.handlebarlink.ui.navigation.rememberContainerTransform

/**
 * The Devices tab with nothing added (Paper "Devices 1 — No intercom yet", "Devices 1L"): the CarPlay tab's "No iPhone
 * yet" hero with the headset (Not set up, "Add your intercom", what pairing it brings), the brand Add device, which
 * grows into Add device as Home's tile does, then what the app does with an intercom, as rows without a chevron.
 * Upright one centred column; on its side the hero | the button and the rows ([HeroPanes]).
 */
@Composable
internal fun NoIntercom(
    onAdd: () -> Unit,
    contentPadding: PaddingValues,
) {
    val transform = rememberContainerTransform(ADD_DEVICE_FROM_DEVICES, expanded = false)
    if (LocalWindowLayout.current == WindowLayout.PANES) {
        HeroPanes(
            contentPadding,
            hero = { Hero() },
            side = {
                AddButton(onAdd, transform, Modifier)
                Intro()
            },
        )
        return
    }
    HeroColumn(contentPadding) {
        val width = Modifier.widthIn(max = HlTheme.sizes.contentMax)
        Column(
            width.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HlTheme.spacing.s12),
        ) {
            Hero()
            AddButton(onAdd, transform, Modifier.padding(top = HlTheme.spacing.s8))
        }
        Intro(width)
    }
}

@Composable
private fun ColumnScope.Hero() =
    HeroState(
        HlIcons.Intercom,
        SignalTone.IDLE,
        progress = 0f,
        tag = stringResource(R.string.intercom_tag_not_set_up),
        title = stringResource(R.string.intercom_add_title),
        detail = stringResource(R.string.intercom_add_detail),
    )

@Composable
private fun AddButton(
    onAdd: () -> Unit,
    transform: ContainerTransform?,
    modifier: Modifier,
) = HlButton(
    stringResource(R.string.add_device),
    onAdd,
    modifier.fillMaxWidth().containerTransform(transform).containerContent(transform),
    brand = true,
)

/** What an intercom brings, one row each: an icon, what it is and a line on it; information only, nothing to tap. */
@Composable
private fun Intro(modifier: Modifier = Modifier) {
    HlGroup(modifier) {
        IntroRows.forEachIndexed { index, row ->
            if (index > 0) HlGroupDivider()
            HlValueRow(
                value = stringResource(row.title),
                hint = null,
                // One item for TalkBack: the title with its line.
                modifier = Modifier.semantics(mergeDescendants = true) {},
                detail = stringResource(row.detail),
                container = Color.Transparent,
                icon = row.icon,
            )
        }
    }
}

private class IntroRow(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
)

private val IntroRows =
    listOf(
        IntroRow(HlIcons.Battery, R.string.intercom_intro_battery, R.string.intercom_intro_battery_detail),
        IntroRow(HlIcons.Music, R.string.intercom_intro_audio, R.string.intercom_intro_audio_detail),
        IntroRow(HlIcons.Voice, R.string.intercom_intro_button, R.string.intercom_intro_button_detail),
    )

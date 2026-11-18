package com.abid.handlebarlink.ui.intercom

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.ui.controls.RiderActionSheet

/** "Assistant button": what the intercom's assistant button does (the shared action chooser). */
@Composable
fun AssistantSheet(
    chosen: RiderAction,
    onChoose: (RiderAction) -> Unit,
    onCancel: () -> Unit,
) {
    RiderActionSheet(
        HlIcons.Intercom,
        stringResource(R.string.assistant_button),
        stringResource(R.string.assistant_detail),
        chosen,
        onChoose,
        onCancel,
    )
}

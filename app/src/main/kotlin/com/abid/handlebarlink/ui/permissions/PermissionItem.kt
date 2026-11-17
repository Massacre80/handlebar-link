package com.abid.handlebarlink.ui.permissions

import androidx.annotation.StringRes

/** One permission row: what it is, why, where it stands, and how to ask. */
data class PermissionItem(
    @param:StringRes val title: Int,
    @param:StringRes val why: Int,
    val status: Access,
    val onAct: () -> Unit,
    /** The pill's text when it is not the permission's own ("Turn on" for Bluetooth). */
    @param:StringRes val action: Int? = null,
)

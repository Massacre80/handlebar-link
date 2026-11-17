package com.abid.handlebarlink.ui.setup

import androidx.annotation.StringRes
import com.abid.handlebarlink.setup.FoundMeter
import com.abid.handlebarlink.setup.SetupProblem
import com.abid.handlebarlink.ui.permissions.Access

/** What the setup screens can ask for; the route turns each into the view model call or system screen. */
data class SetupActions(
    val onStart: () -> Unit,
    val onBack: () -> Unit,
    val onFind: () -> Unit,
    val onFix: (SetupProblem) -> Unit,
    val onChoose: (FoundMeter) -> Unit,
    val onSearchAgain: () -> Unit,
    val onNotifications: () -> Unit,
    val onLocation: () -> Unit,
    val onHome: () -> Unit,
)

/** The permissions "You're set" asks for; Home opens by itself once [complete]. */
data class SetupAccess(
    val notifications: Access,
    val location: Access,
) {
    /** Both allowed. */
    val complete: Boolean get() = notifications == Access.GRANTED && location == Access.GRANTED

    /** One was refused for good: the rider may continue without it. */
    val blocked: Boolean get() = notifications == Access.BLOCKED || location == Access.BLOCKED
}

/** Where the search stands: running, ended with nothing found (danger), or ended after finding meters. */
internal enum class SearchState { SEARCHING, NONE_FOUND, ENDED }

/** One permission row: what it is, why, where it stands, and how to ask. */
internal data class PermissionItem(
    @param:StringRes val title: Int,
    @param:StringRes val why: Int,
    val status: Access,
    val onAct: () -> Unit,
)

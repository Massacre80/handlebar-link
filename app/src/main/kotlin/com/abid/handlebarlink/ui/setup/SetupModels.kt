package com.abid.handlebarlink.ui.setup

import com.abid.handlebarlink.R
import com.abid.handlebarlink.setup.FoundMeter
import com.abid.handlebarlink.setup.SetupProblem
import com.abid.handlebarlink.ui.permissions.Access
import com.abid.handlebarlink.ui.permissions.PermissionItem

/** What the setup screens can ask for; the route turns each into the view model call or system screen. */
data class SetupActions(
    val onStart: () -> Unit,
    val onBack: () -> Unit,
    val onFind: () -> Unit,
    val onFix: (SetupProblem) -> Unit,
    val onChoose: (FoundMeter) -> Unit,
    val onSearchAgain: () -> Unit,
    val onNotifications: () -> Unit,
    val onHome: () -> Unit,
)

/**
 * The permissions "You're set" asks for: the notifications only (location, for logging rides, went with the Rides
 * feature; owner, 2026-11-20). Home opens by itself once [complete].
 */
data class SetupAccess(
    val notifications: Access,
) {
    /** All allowed. */
    val complete: Boolean get() = notifications == Access.GRANTED

    /** One was refused for good: the rider may continue without it. */
    val blocked: Boolean get() = notifications == Access.BLOCKED

    /** The rows "You're set" lists, each with its call to action from [actions]. */
    fun rows(actions: SetupActions): List<PermissionItem> =
        listOf(
            PermissionItem(
                R.string.permission_notifications,
                R.string.permission_notifications_why,
                notifications,
                actions.onNotifications,
            ),
        )
}

/** Where the search stands: running, ended with nothing found (danger), or ended after finding meters. */
internal enum class SearchState { SEARCHING, NONE_FOUND, ENDED }

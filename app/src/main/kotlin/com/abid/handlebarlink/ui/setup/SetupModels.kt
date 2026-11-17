package com.abid.handlebarlink.ui.setup

import com.abid.handlebarlink.setup.FoundMeter
import com.abid.handlebarlink.setup.SetupProblem

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

/** Where the search stands: running, ended with nothing found (danger), or ended after finding meters. */
internal enum class SearchState { SEARCHING, NONE_FOUND, ENDED }

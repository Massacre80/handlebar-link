package com.abid.handlebarlink.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.data.AppearanceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings: the rider's theme choice. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val appearance: AppearanceSettings,
    ) : ViewModel() {
        /** The chosen theme. */
        val theme: StateFlow<AppTheme> =
            appearance.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppTheme.NIGHT)

        /** Stores [theme]; the whole app switches at once. */
        fun choose(theme: AppTheme) {
            viewModelScope.launch { appearance.setTheme(theme) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

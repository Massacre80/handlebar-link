package com.abid.handlebarlink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.data.AppTheme
import com.abid.handlebarlink.data.AppearanceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** App-wide UI state: the theme. */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        appearance: AppearanceSettings,
    ) : ViewModel() {
        /** The rider's theme (Night until loaded). */
        val theme: StateFlow<AppTheme> =
            appearance.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppTheme.NIGHT)

        private companion object {
            /** Keeps the flow across configuration changes (Android architecture recommendations). */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

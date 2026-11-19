package com.abid.handlebarlink.ui.handlebar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.ActionAvailability
import com.abid.handlebarlink.controls.CallSituation
import com.abid.handlebarlink.controls.HandlebarMapping
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.ui.navigation.HandlebarResetRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The Handlebar page and its chooser (docs/PLAN.md "Smart buttons"): the situation the rider is looking at
 * (riding, ringing, on a call), every press's action in it with whether it works with today's CarPlay, and the
 * chooser's choices for a situation ([pickerState]). Changes go through [HandlebarMapping.assign]; an action
 * that needs our own CarPlay may be chosen (the defaults have them too); it is marked "Soon" and does nothing
 * until then.
 */
@HiltViewModel
class HandlebarViewModel
    @Inject
    constructor(
        private val mapping: HandlebarMapping,
        private val availability: ActionAvailability,
    ) : ViewModel() {
        private val situation = MutableStateFlow(CallSituation.RIDING)

        /** The page: the chosen situation and its presses. */
        val uiState: StateFlow<HandlebarUiState> =
            combine(situation, mapping.current, availability.changes) { shown, all, _ ->
                handlebarState(shown, all, availability)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                handlebarState(situation.value, mapping.current.value, availability),
            )

        /** Shows [situation]'s presses. */
        fun select(situation: CallSituation) {
            this.situation.value = situation
        }

        /** The chooser for [switch] in [situation], live: its choices and the press's action now. */
        fun picker(
            situation: CallSituation,
            switch: HandlebarSwitch,
        ): Flow<PickerUiState> =
            combine(
                mapping.current,
                availability.changes,
            ) { all, _ -> pickerState(situation, switch, all, availability) }

        /** The chooser's first frame, before [picker] has emitted. */
        fun pickerNow(
            situation: CallSituation,
            switch: HandlebarSwitch,
        ): PickerUiState = pickerState(situation, switch, mapping.current.value, availability)

        /** Makes [switch] do [action] in [situation]; returns once stored, false when not offered there. */
        suspend fun assignNow(
            situation: CallSituation,
            switch: HandlebarSwitch,
            action: RiderAction,
        ): Boolean = mapping.assign(situation, switch, action)

        /**
         * Every situation back to the defaults; returns once stored. Only the confirmation dialog calls it
         * ([HandlebarResetRoute]); the page's button only opens that dialog.
         */
        suspend fun resetNow() {
            mapping.reset()
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

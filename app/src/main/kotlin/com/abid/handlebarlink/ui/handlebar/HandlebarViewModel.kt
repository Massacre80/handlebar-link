package com.abid.handlebarlink.ui.handlebar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import com.abid.handlebarlink.controls.HandlebarMapping
import com.abid.handlebarlink.controls.RiderAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The handlebar buttons page and its chooser: the rider's mapping, changed here. */
@HiltViewModel
class HandlebarViewModel
    @Inject
    constructor(
        private val mapping: HandlebarMapping,
    ) : ViewModel() {
        /** What each press does now. */
        val current: StateFlow<Map<HandlebarSwitch, RiderAction>> = mapping.current

        /** Makes [switch] do [action]; returns once stored. */
        suspend fun assignNow(
            switch: HandlebarSwitch,
            action: RiderAction,
        ) = mapping.assign(switch, action)

        /** Back to the defaults. */
        fun reset() {
            viewModelScope.launch { mapping.reset() }
        }
    }

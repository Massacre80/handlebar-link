package com.abid.handlebarlink.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.devices.DeviceSettings
import com.abid.handlebarlink.devices.SavedDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The "What is it?" sheet: saves the device; [saved] turns true once it is stored, and the sheet closes. */
@HiltViewModel
class DeviceSheetViewModel
    @Inject
    constructor(
        private val devices: DeviceSettings,
    ) : ViewModel() {
        private val stored = MutableStateFlow(false)

        /** The device is saved. */
        val saved: StateFlow<Boolean> = stored.asStateFlow()

        /** Saves [device]; a blank name falls back to its model, the device's own name. */
        fun save(device: SavedDevice) {
            viewModelScope.launch {
                devices.save(device.copy(name = device.name.trim().ifEmpty { device.model }))
                stored.value = true
            }
        }
    }

/** What the sheet shows about the device (from the list). */
data class SheetDevice(
    val name: String,
    val audio: Boolean,
    val connected: Boolean,
)

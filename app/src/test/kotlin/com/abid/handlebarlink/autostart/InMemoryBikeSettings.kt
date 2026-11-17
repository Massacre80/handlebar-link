package com.abid.handlebarlink.autostart

import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.SelectedBike
import kotlinx.coroutines.flow.MutableStateFlow

/** [BikeSettings] in memory; the test sets [bike] directly. */
class InMemoryBikeSettings : BikeSettings {
    override val bike = MutableStateFlow<SelectedBike?>(null)

    override suspend fun select(bike: SelectedBike) {
        this.bike.value = bike
    }

    override suspend fun forget() {
        bike.value = null
    }
}

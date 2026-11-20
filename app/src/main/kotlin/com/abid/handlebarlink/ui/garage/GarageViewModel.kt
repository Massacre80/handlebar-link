package com.abid.handlebarlink.ui.garage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.SelectedBike
import com.abid.handlebarlink.di.IoDispatcher
import com.abid.handlebarlink.garage.GarageData
import com.abid.handlebarlink.garage.GarageHero
import com.abid.handlebarlink.garage.GarageSchedule
import com.abid.handlebarlink.garage.GarageStatus
import com.abid.handlebarlink.garage.GarageStore
import com.abid.handlebarlink.garage.daysSince
import com.abid.handlebarlink.garage.garageHero
import com.abid.handlebarlink.garage.garageStatus
import com.abid.handlebarlink.garage.hasRecords
import com.abid.handlebarlink.garage.today
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Garage today, ready to draw: the records, what is due ([status]), the hero and the linked meter. */
@Immutable
data class GarageUiState(
    /** False until the records are read (the page shows only its ground until then). */
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.ofEpochDay(0),
    val data: GarageData = GarageData(),
    val status: GarageStatus = GarageStatus(null, emptyList(), null, null),
    val hero: GarageHero = GarageHero.Empty,
    /** Days since the latest odometer reading; null without one. */
    val readingDaysAgo: Long? = null,
    /** The linked meter, if any. */
    val meter: SelectedBike? = null,
)

/** Builds the page's state from the records and the meter on [today]. */
fun garageUiState(
    data: GarageData,
    meter: SelectedBike?,
    today: LocalDate,
    zone: ZoneId,
): GarageUiState {
    val status = garageStatus(data, today)
    return GarageUiState(
        loaded = true,
        today = today,
        data = data,
        status = status,
        hero = garageHero(data.hasRecords, status),
        readingDaysAgo = status.latest?.let { daysSince(it, today, zone) },
        meter = meter,
    )
}

/**
 * Garage: the records and the meter as one state, and the rider's changes. Every change is stored in one DataStore
 * transaction and returns once written (a sheet closes after its save); saving also makes sure the daily reminder
 * check is scheduled.
 */
@HiltViewModel
class GarageViewModel
    @Inject
    constructor(
        private val store: GarageStore,
        private val bikes: BikeSettings,
        private val time: TimeProvider,
        private val schedule: GarageSchedule,
        private val logger: Logger,
        @param:IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        /** The page's state. */
        val uiState: StateFlow<GarageUiState> =
            combine(store.data, bikes.bike) { data, meter ->
                garageUiState(data, meter, today(), time.clock.zone)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GarageUiState())

        /** Today in the phone's time zone. */
        fun today(): LocalDate = time.clock.today()

        /** Now, for a reading's time. */
        fun nowMillis(): Long = time.clock.millis()

        /** The phone's time zone. */
        fun zone(): ZoneId = time.clock.zone

        /** Applies [change] and stores it; false when it could not be written (the sheet stays open). */
        suspend fun save(change: (GarageData) -> GarageData): Boolean =
            try {
                store.update(change)
                withContext(io) { schedule.ensure() }
                true
            } catch (e: IOException) {
                logger.error("garage.save_failed", e)
                false
            }

        /**
         * "Forget this bike": the app stops linking to this meter (the selection is cleared, so presence observing
         * stops and setup shows); Garage's records stay. The companion association is kept (docs/PLAN.md "Garage").
         * Suspends until stored: the dialog that asks closes only afterwards, as its scope ends with it.
         */
        suspend fun forgetBike() {
            bikes.forget()
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

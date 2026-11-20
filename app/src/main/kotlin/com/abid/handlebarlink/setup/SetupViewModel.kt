package com.abid.handlebarlink.setup

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.data.BikeSettings
import com.abid.handlebarlink.data.SelectedBike
import com.abid.handlebarlink.steady.stableState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** The setup screens, in order (Paper "Setup"; Android's own sheets appear during LINKING and PAIRING). */
enum class SetupStep {
    /** "Link your CB350". */
    WELCOME,

    /** "Get the bike ready": the meter steps, then Find my bike. */
    READY,

    /** "Choose your bike": what our scan found. */
    CHOOSE,

    /** Android's consent sheet for the chosen meter. */
    LINKING,

    /** Pairing, with Android's pairing request. */
    PAIRING,

    /** "You're set". */
    DONE,
}

/** Setup's one state. [consent] is Android's consent screen to launch once (then [SetupViewModel.consentLaunched]). */
data class SetupUiState(
    val step: SetupStep = SetupStep.WELCOME,
    val meters: List<FoundMeter> = emptyList(),
    val paired: List<FoundMeter> = emptyList(),
    val searching: Boolean = false,
    val chosen: FoundMeter? = null,
    val consent: IntentSender? = null,
    val linkedAddress: String? = null,
    val problem: SetupProblem? = null,
)

/**
 * First-time setup (docs/PLAN.md "First-time setup"): conditions, our scan, Android's consent, pairing, then
 * the bike is saved when the rider leaves "You're set". Permissions are asked by the screen before
 * [findBike] (they need the Activity); everything else happens here.
 */
@HiltViewModel
@Suppress("LongParameterList") // One per collaborator Hilt injects; the clock times the steady pages.
class SetupViewModel
    @Inject
    constructor(
        private val conditions: SetupConditions,
        private val scanner: MeterScanner,
        private val association: MeterAssociation,
        private val bonding: MeterBonding,
        private val bikes: BikeSettings,
        time: TimeProvider,
        logger: Logger,
    ) : ViewModel() {
        private val log = logger.child("setup")
        private val state = MutableStateFlow(SetupUiState())
        private var scan: Job? = null

        /** Setup's state, steady ([steady]): a wait that ends at once (a meter already paired) never flashes. */
        val uiState: StateFlow<SetupUiState> =
            state.steady(time).stateIn(viewModelScope, SharingStarted.Eagerly, state.value)

        /** Welcome → the meter steps. */
        fun start() {
            state.update { it.copy(step = SetupStep.READY, problem = null) }
        }

        /** One step back (the system Back on setup screens); false when there is none (leave the app). */
        fun back(): Boolean {
            val previous =
                when (state.value.step) {
                    SetupStep.READY -> SetupStep.WELCOME
                    SetupStep.CHOOSE, SetupStep.LINKING, SetupStep.PAIRING -> SetupStep.READY
                    SetupStep.WELCOME, SetupStep.DONE -> return false
                }
            scan?.cancel()
            state.update { SetupUiState(step = previous) }
            return true
        }

        /** Find my bike: needs Bluetooth and Location Services on (companion-device pairing), then scans. */
        fun findBike() {
            val problem =
                when {
                    !conditions.bluetoothOn() -> SetupProblem.BLUETOOTH_OFF
                    !conditions.locationOn() -> SetupProblem.LOCATION_OFF
                    else -> null
                }
            if (problem != null) {
                state.update { it.copy(problem = problem) }
                return
            }
            state.update { it.copy(step = SetupStep.CHOOSE, meters = emptyList(), problem = null) }
            viewModelScope.launch {
                val paired = scanner.paired()
                state.update { it.copy(paired = paired) }
            }
            search()
        }

        /** The permission was refused: setup cannot search. */
        fun permissionRefused() {
            state.update { it.copy(problem = SetupProblem.PERMISSION_MISSING) }
        }

        /** Searches (again) for a while; results keep coming in until the time limit (find-ble-devices). */
        fun search() {
            scan?.cancel()
            state.update { it.copy(searching = true, problem = null) }
            scan =
                viewModelScope.launch {
                    try {
                        withTimeoutOrNull(SCAN_MS) {
                            scanner.scan().collect { found ->
                                // A paired meter that still advertises is listed once, as paired.
                                state.update { now ->
                                    now.copy(
                                        meters =
                                            found.filter { meter ->
                                                now.paired.none {
                                                    it.address ==
                                                        meter.address
                                                }
                                            },
                                    )
                                }
                            }
                        }
                    } catch (e: SetupException) {
                        log.warn("scan.problem", "problem" to e.problem)
                        state.update { it.copy(problem = e.problem) }
                    } finally {
                        state.update { it.copy(searching = false) }
                    }
                }
        }

        /**
         * The rider tapped [meter]: stop scanning (associate() doc) and ask Android to link it; a meter this
         * phone already paired goes straight to "You're set".
         */
        fun choose(meter: FoundMeter) {
            scan?.cancel()
            if (meter.paired) {
                // Linked and paired already: nothing for Android to ask.
                state.update {
                    it.copy(
                        step = SetupStep.DONE,
                        chosen = meter,
                        linkedAddress = meter.address,
                        problem = null,
                    )
                }
                return
            }
            state.update { it.copy(step = SetupStep.LINKING, chosen = meter, problem = null) }
            viewModelScope.launch {
                try {
                    val consent = association.request(meter)
                    state.update { it.copy(consent = consent) }
                } catch (e: SetupException) {
                    state.update { it.copy(step = SetupStep.CHOOSE, problem = e.problem) }
                }
            }
        }

        /** The screen launched Android's consent screen. */
        fun consentLaunched() {
            state.update { it.copy(consent = null) }
        }

        /** Android's consent screen closed. Linked: pair; otherwise back to the list. */
        fun onConsentResult(
            resultCode: Int,
            data: Intent?,
        ) {
            val address = association.addressFrom(resultCode, data)
            if (address == null) {
                state.update { it.copy(step = SetupStep.CHOOSE, problem = SetupProblem.NOT_LINKED) }
                return
            }
            state.update { it.copy(step = SetupStep.PAIRING, linkedAddress = address) }
            pair()
        }

        /** Pairs the linked meter (again, after a failure). */
        fun pair() {
            val address = state.value.linkedAddress ?: return
            state.update { it.copy(problem = null) }
            viewModelScope.launch {
                if (bonding.bond(address)) {
                    state.update { it.copy(step = SetupStep.DONE) }
                } else {
                    state.update { it.copy(problem = SetupProblem.PAIRING_FAILED) }
                }
            }
        }

        /** Go to Home: saves the bike, which ends setup (the app shows the tabs once a bike is chosen). */
        fun finish() {
            val current = state.value
            val address = current.linkedAddress ?: return
            viewModelScope.launch { bikes.select(SelectedBike(address, current.chosen?.name ?: address)) }
        }

        private companion object {
            /** A time limit for every scan (find-ble-devices); RoadSync uses 10 s, a little longer helps. */
            const val SCAN_MS = 15_000L
        }
    }

/**
 * Setup's state as its pages show it (docs/PLAN.md "Stable live indicators"): Linking and Pairing are waits for
 * Android, shown only once they last the show delay and then kept the minimum time, as AndroidX's
 * `ContentLoadingProgressBar` does for progress. Every other page, a problem and a new consent screen to launch
 * show at once; the list, the search and the rest of a page change in place.
 */
internal fun Flow<SetupUiState>.steady(time: TimeProvider): Flow<SetupUiState> =
    stableState(
        time,
        stateOf = { it.step to it.problem },
        immediate = { shown, next ->
            next.problem != null || (next.consent != null && next.consent != shown.consent) || next.step !in WAITS
        },
    )

/** The pages that wait for Android. */
private val WAITS = setOf(SetupStep.LINKING, SetupStep.PAIRING)

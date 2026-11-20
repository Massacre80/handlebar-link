package com.abid.handlebarlink.ui.carplay

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlayCoordinator
import com.abid.handlebarlink.carplay.IphonePairing
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.android.pairing.PairingProblem
import com.abid.handlebarlink.core.common.time.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The pairing screens (Paper "CarPlay — flow v2", iPhone 1–3). */
enum class PairStep {
    /** "Pair your iPhone": the steps and "Make this phone visible" (iPhone 1). */
    STEPS,

    /** "Waiting for your iPhone": this phone visible, its name and the time left (iPhone 2a). */
    WAITING,

    /** "Check the code": Android shows its code dialog (iPhone 2b). */
    PAIRING,

    /** "Paired": the iPhone, Start CarPlay and Done (iPhone 3). */
    PAIRED,
}

/** What stops the pairing, with its one fix. */
enum class PairProblem {
    /** Nearby devices is not allowed (fix: ask). */
    NOT_ALLOWED,

    /** Bluetooth is off (fix: make visible, which turns it on). */
    BLUETOOTH_OFF,

    /** The rider refused Android's "make visible" dialog (fix: ask again). */
    NOT_VISIBLE,

    /** The bond did not form (fix: make visible again and retry from the iPhone). */
    FAILED,

    /** The phone that paired is not an iPhone. */
    NOT_AN_IPHONE,
}

/** The pairing screens' state. */
@Immutable
data class PairIphoneUiState(
    val step: PairStep = PairStep.STEPS,
    /** This phone's Bluetooth name: what the rider taps on the iPhone. */
    val localName: String? = null,
    /** Seconds this phone stays visible; 0 when it no longer is; null before it was made visible. */
    val secondsLeft: Long? = null,
    /** The phone pairing (PAIRING), or the device a problem is about. */
    val otherName: String? = null,
    /** The paired iPhone (PAIRED). */
    val paired: PairedIphone? = null,
    val problem: PairProblem? = null,
)

/**
 * In-app iPhone pairing (CarLink's approach, carplay/IphonePairing): the rider makes this phone visible (Android's
 * dialog, 300 s), pairs from the iPhone's Bluetooth settings, compares the code Android shows, and the paired iPhone
 * becomes CarPlay's iPhone. Bluetooth is watched only while the screen is collected; after the permission is granted
 * the session starts again ([restart]).
 */
@HiltViewModel
class PairIphoneViewModel
    @Inject
    constructor(
        private val pairing: IphonePairing,
        private val coordinator: CarPlayCoordinator,
        private val time: TimeProvider,
    ) : ViewModel() {
        private val sessions = MutableStateFlow(0)

        /** When this phone stops being visible (elapsed millis), after Android granted it. */
        private val visibleUntil = MutableStateFlow<Long?>(null)
        private val refused = MutableStateFlow(false)

        /** The iPhone already chosen in this flow (it is chosen once, when it pairs). */
        private var chosen: String? = null

        @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
        private val session: Flow<IphonePairingState> = sessions.flatMapLatest { pairing.session() }

        @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
        private val secondsLeft: Flow<Long?> =
            visibleUntil.flatMapLatest { until ->
                until?.let(::countdown)
                    ?: flowOf(null)
            }

        /** The screens' state. */
        val uiState: StateFlow<PairIphoneUiState> =
            combine(session.onEach(::choosePaired), secondsLeft, refused, ::pairIphoneState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PairIphoneUiState())

        /** Android made this phone visible for [seconds] (the dialog's result code). */
        fun visible(seconds: Int) {
            refused.value = false
            visibleUntil.value = time.elapsedMillis() + seconds * MILLIS_PER_SECOND
        }

        /** The rider refused Android's dialog (or it failed). */
        fun notVisible() {
            refused.value = true
        }

        /** The permission was granted: watch Bluetooth again. */
        fun restart() {
            sessions.update { it + 1 }
        }

        /** Renames this phone's Bluetooth to [name] (the bike's). */
        fun rename(name: String) {
            viewModelScope.launch { pairing.rename(name) }
        }

        /** Start CarPlay: any stop lifted (or a failed engine started again); the caller starts the ride service. */
        fun startCarPlay() {
            coordinator.start()
        }

        /** A paired iPhone becomes CarPlay's iPhone, once. */
        private suspend fun choosePaired(state: IphonePairingState) {
            val iphone = (state as? IphonePairingState.Paired)?.iphone ?: return
            if (chosen == iphone.address) return
            chosen = iphone.address
            pairing.choose(iphone.address)
        }

        /** Whole seconds to [until], rounded up, every second, ending at 0. */
        private fun countdown(until: Long): Flow<Long> =
            flow {
                while (true) {
                    val left = until - time.elapsedMillis()
                    emit(((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(0))
                    if (left <= 0) break
                    delay((left - 1) % MILLIS_PER_SECOND + 1)
                }
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val MILLIS_PER_SECOND = 1_000L
        }
    }

/**
 * The screen for the [session]'s state, the visible [secondsLeft] (null: never made visible) and whether the rider
 * [refused] Android's dialog. A pairing in progress or done always shows, whatever the visibility; once made visible
 * the screen waits (also after the time ran out, with "make visible again").
 */
internal fun pairIphoneState(
    session: IphonePairingState,
    secondsLeft: Long?,
    refused: Boolean,
): PairIphoneUiState {
    val waitingStep = if (secondsLeft != null) PairStep.WAITING else PairStep.STEPS
    return when (session) {
        IphonePairingState.NotAllowed -> {
            PairIphoneUiState(waitingStep, secondsLeft = secondsLeft, problem = PairProblem.NOT_ALLOWED)
        }

        IphonePairingState.BluetoothOff -> {
            PairIphoneUiState(waitingStep, secondsLeft = secondsLeft, problem = PairProblem.BLUETOOTH_OFF)
        }

        is IphonePairingState.Waiting -> {
            val (problem, other) =
                when (val it = session.problem) {
                    is PairingProblem.Failed -> PairProblem.FAILED to it.name
                    is PairingProblem.NotAnIphone -> PairProblem.NOT_AN_IPHONE to it.name
                    null -> (if (refused) PairProblem.NOT_VISIBLE else null) to null
                }
            PairIphoneUiState(waitingStep, session.localName, secondsLeft, other, problem = problem)
        }

        is IphonePairingState.Pairing -> {
            PairIphoneUiState(PairStep.PAIRING, secondsLeft = secondsLeft, otherName = session.name)
        }

        is IphonePairingState.Checking -> {
            PairIphoneUiState(PairStep.PAIRING, secondsLeft = secondsLeft, otherName = session.name)
        }

        is IphonePairingState.Paired -> {
            PairIphoneUiState(PairStep.PAIRED, otherName = session.iphone.name, paired = session.iphone)
        }
    }
}

package com.abid.handlebarlink.controls

import com.abid.handlebarlink.callaudio.VoipCallWatcher
import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** What is happening with calls when a handlebar key is pressed; each situation has its own layer of actions. */
enum class CallSituation {
    /** No call: music, Siri, the phone. */
    RIDING,

    /** A call is ringing. */
    RINGING,

    /** A call is on: active, held or being dialled. */
    ON_CALL,
}

/**
 * Where the [CallSituation] comes from: our own CarPlay's iAP2 call states while it is connected, else the phone's
 * audio (CarLink) ([RoutedCallSituations]).
 */
interface CallSituations {
    /** The situation now; a press reads it to choose its layer. */
    val current: StateFlow<CallSituation>

    /**
     * Whether [current] can be [CallSituation.RINGING] now. When false, presses while a call rings use the
     * [CallSituation.RIDING] layer, and the UI can say the ringing layer needs our own CarPlay
     * ([ActionAvailability.whyUnavailable]).
     */
    val seesRinging: Boolean
}

/**
 * The situation from our own CarPlay while it is connected ([OurCarPlay]: the iPhone's iAP2 CallStateUpdates through
 * [situationOfCalls], so a ringing call is seen), else from the phone's audio ([AudioCallSituations], never ringing).
 * Watched for the process's lifetime like the audio source, so presses and the microphone's auto-unmute
 * ([CarPlayControls]) always see the current situation. [current] is built on first use (the audio source reads the
 * audio state then; docs/LESSONS.md 60); the first use is CarPlayControls.start's coroutine.
 */
@Singleton
class RoutedCallSituations(
    private val carPlay: OurCarPlay,
    private val audio: CallSituations,
    private val scope: CoroutineScope,
    logger: Logger,
) : CallSituations {
    @Inject
    constructor(
        carPlay: OurCarPlay,
        audio: AudioCallSituations,
        @ApplicationScope scope: CoroutineScope,
        logger: Logger,
    ) : this(carPlay, audio as CallSituations, scope, logger)

    private val log = logger.child("control")

    override val current: StateFlow<CallSituation> by lazy {
        val fromAudio = audio.current
        combine(carPlay.connected, carPlay.calls, fromAudio) { connected, calls, heard ->
            RoutedSituation(connected, routedSituation(connected, calls, heard))
        }.distinctUntilChanged()
            .onEach {
                log.info(
                    "control.situation.routed",
                    "situation" to it.situation,
                    "source" to if (it.fromCarPlay) "our_carplay" else "audio",
                )
            }.map { it.situation }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                routedSituation(carPlay.isConnected, carPlay.calls.value, fromAudio.value),
            )
    }

    override val seesRinging: Boolean get() = carPlay.isConnected

    private data class RoutedSituation(
        val fromCarPlay: Boolean,
        val situation: CallSituation,
    )
}

/** [calls] while our CarPlay is [connected] ([situationOfCalls]), else what the phone's audio says ([heard]). */
internal fun routedSituation(
    connected: Boolean,
    calls: List<CarPlayCall>,
    heard: CallSituation,
): CallSituation = if (connected) situationOfCalls(calls.map { it.status.iap2 }) else heard

/**
 * Interim [CallSituations] while CarPlay runs in CarLink: [CallSituation.ON_CALL] while the phone's audio shows
 * a call ([VoipCallWatcher]: a VOICE_COMMUNICATION recording and playback together), else
 * [CallSituation.RIDING]. Never [CallSituation.RINGING]: CarLink does not show a ringing call to other apps, and
 * a ring is not a call in the audio state (docs/LESSONS.md 44). Watched for the process's lifetime, so a press
 * and the microphone's auto-unmute ([CarPlayControls]) always see the current situation.
 */
@Singleton
class AudioCallSituations
    @Inject
    constructor(
        watcher: VoipCallWatcher,
        @ApplicationScope scope: CoroutineScope,
        logger: Logger,
    ) : CallSituations {
        private val log = logger.child("control")

        // Seeded with one read of the audio state, so a process restarted mid-call is on a call from the first
        // read on (CarPlayControls.start must not take it for riding and unmute a muted call). Built on first
        // use, not here: this is constructed in HandlebarLinkApp.onCreate on the main thread, and the read is two
        // Binder calls to the audio service; the first use is CarPlayControls.start's coroutine (Default).
        override val current: StateFlow<CallSituation> by lazy {
            situationsFrom(watcher.inCall(), scope, situationOf(watcher.inCallNow())) {
                log.info("control.situation", "situation" to it)
            }
        }

        override val seesRinging: Boolean = false
    }

/**
 * [CallSituation.ON_CALL] while [inCall] is true, else [CallSituation.RIDING], starting at [initial]; [onChange]
 * sees each new one.
 */
internal fun situationsFrom(
    inCall: Flow<Boolean>,
    scope: CoroutineScope,
    initial: CallSituation = CallSituation.RIDING,
    onChange: (CallSituation) -> Unit = {},
): StateFlow<CallSituation> =
    inCall
        .map(::situationOf)
        .distinctUntilChanged()
        .onEach(onChange)
        .stateIn(scope, SharingStarted.Eagerly, initial)

private fun situationOf(inCall: Boolean): CallSituation = if (inCall) CallSituation.ON_CALL else CallSituation.RIDING

/**
 * The situation from the iPhone's calls ([RoutedCallSituations], fed by [OurCarPlay.calls]), each an iAP2
 * CallStateUpdate (0x4155) status: 0 disconnected, 1 sending, 2 ringing, 3 connecting, 4 active, 5 held,
 * 6 disconnecting (DiPlay `BydCarPlayCall.kt:22-24`). A ringing call wins (call waiting: the keys answer or decline
 * it); any other live call is [CallSituation.ON_CALL]; none, or only disconnected or unknown statuses, is riding.
 */
fun situationOfCalls(statuses: Collection<Int>): CallSituation =
    when {
        IAP2_RINGING in statuses -> CallSituation.RINGING
        statuses.any { it in IAP2_ON_CALL } -> CallSituation.ON_CALL
        else -> CallSituation.RIDING
    }

private const val IAP2_SENDING = 1
private const val IAP2_RINGING = 2
private const val IAP2_CONNECTING = 3
private const val IAP2_ACTIVE = 4
private const val IAP2_HELD = 5
private const val IAP2_DISCONNECTING = 6

/** Sending (dialling), connecting, active, held and disconnecting: the call still has the rider. */
private val IAP2_ON_CALL = setOf(IAP2_SENDING, IAP2_CONNECTING, IAP2_ACTIVE, IAP2_HELD, IAP2_DISCONNECTING)

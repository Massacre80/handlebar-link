package com.abid.handlebarlink.controls

import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** Where the [CallSituation] comes from: our own CarPlay's iAP2 call states ([CarPlayCallSituations]). */
interface CallSituations {
    /** The situation now; a press reads it to choose its layer. */
    val current: StateFlow<CallSituation>
}

/**
 * The situation from our own CarPlay ([OurCarPlay]: the iPhone's iAP2 CallStateUpdates through [situationOfCalls], so a
 * ringing call is seen) while it is connected; [CallSituation.RIDING] while it is not, since no call reaches the
 * handlebar then (our CarPlay replaced CarLink, owner 2026-11-20; nothing else on the phone carries calls). Watched for
 * the process's lifetime, so presses and the microphone's auto-unmute ([CarPlayControls]) always see the current
 * situation; a CarPlay that drops mid-call is riding at once, which unmutes a microphone this app muted (the safe
 * side). [current] is built on first use (CarPlayControls.start's coroutine), never while Hilt builds this in
 * HandlebarLinkApp.onCreate (docs/LESSONS.md 60).
 */
@Singleton
class CarPlayCallSituations
    @Inject
    constructor(
        private val carPlay: OurCarPlay,
        @param:ApplicationScope private val scope: CoroutineScope,
        logger: Logger,
    ) : CallSituations {
        private val log = logger.child("control")

        override val current: StateFlow<CallSituation> by lazy {
            combine(carPlay.connected, carPlay.calls, ::carPlaySituation)
                .distinctUntilChanged()
                .onEach { log.info("control.situation", "situation" to it) }
                .stateIn(scope, SharingStarted.Eagerly, carPlaySituation(carPlay.isConnected, carPlay.calls.value))
        }
    }

/** The iPhone's [calls] while our CarPlay is [connected] ([situationOfCalls]), else riding. */
internal fun carPlaySituation(
    connected: Boolean,
    calls: List<CarPlayCall>,
): CallSituation = if (connected) situationOfCalls(calls.map { it.status.iap2 }) else CallSituation.RIDING

/**
 * The situation from the iPhone's calls ([CarPlayCallSituations], fed by [OurCarPlay.calls]), each an iAP2
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

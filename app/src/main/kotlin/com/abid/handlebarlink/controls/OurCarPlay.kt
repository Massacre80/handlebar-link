package com.abid.handlebarlink.controls

import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayKey
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Handlebar Link's own CarPlay receiver as the smart buttons see it (docs/PLAN.md "Smart buttons"): whether it is
 * connected to the iPhone now, the iPhone's calls, and the keys and Siri it can send. [link] is null when the phone
 * has no CarPlay ports; then it is never connected. Connected is [CarPlayState.Connected].
 * Building it only keeps references (it is built in HandlebarLinkApp.onCreate, docs/LESSONS.md 60).
 */
class OurCarPlay
    @Inject
    constructor(
        private val link: CarPlayLink?,
    ) {
        /** What our CarPlay is doing ([CarPlayLink.state]); always stopped without a link. */
        val state: StateFlow<CarPlayState> = link?.state ?: MutableStateFlow(CarPlayState.Stopped)

        /** Whether our CarPlay is connected to the iPhone now. */
        val isConnected: Boolean get() = link?.state?.value is CarPlayState.Connected

        /** [isConnected] as it changes. */
        val connected: Flow<Boolean> =
            link
                ?.state
                ?.map { it is CarPlayState.Connected }
                ?.distinctUntilChanged()
                ?: flowOf(false)

        /** The iPhone's calls from iAP2 ([CarPlayLink.calls]); empty without a link. */
        val calls: StateFlow<List<CarPlayCall>> = link?.calls ?: MutableStateFlow(emptyList())

        /** One press of [key] on the iPhone ([CarPlayLink.press]). */
        fun press(key: CarPlayKey) {
            link?.press(key)
        }

        /** Opens Siri on the iPhone ([CarPlayLink.requestSiri]). */
        fun requestSiri() {
            link?.requestSiri()
        }
    }

/**
 * [ControlTarget] through our own CarPlay, the only one (it replaced CarLink, owner 2026-11-20): the music keys,
 * answer, decline and end as HID keys and Siri as `requestSiri`, as DiPlay sends them (docs/PLAN.md "Smart buttons"):
 * answer is the telephony Hook Switch, decline and end are both Drop (DiPlay CarPlayController.answerCall and endCall,
 * CarPlayController.kt:750-753). While our CarPlay is not connected a press does nothing: it is logged
 * (`control.carplay.not_connected`) and comes back [Unavailable.NO_CARPLAY_SESSION].
 */
class OurCarPlayControlTarget
    @Inject
    constructor(
        private val carPlay: OurCarPlay,
        logger: Logger,
    ) : ControlTarget {
        private val log = logger.child("control")

        override fun perform(action: ControlAction): ControlResult {
            if (!carPlay.isConnected) {
                log.info("control.carplay.not_connected", "action" to action)
                return ControlResult.NotDone(Unavailable.NO_CARPLAY_SESSION)
            }
            val key = CARPLAY_KEYS[action]
            if (key == null) carPlay.requestSiri() else carPlay.press(key)
            log.info("control.carplay", "action" to action, "key" to (key ?: "siri"))
            return ControlResult.Done
        }
    }

/** The HID key each action presses; Siri has none (it is `requestSiri`). */
private val CARPLAY_KEYS: Map<ControlAction, CarPlayKey> =
    mapOf(
        ControlAction.PLAY_PAUSE to CarPlayKey.PLAY_PAUSE,
        ControlAction.NEXT to CarPlayKey.NEXT,
        ControlAction.PREVIOUS to CarPlayKey.PREVIOUS,
        ControlAction.ANSWER_CALL to CarPlayKey.HOOK_SWITCH,
        ControlAction.DECLINE_CALL to CarPlayKey.DROP,
        ControlAction.END_CALL to CarPlayKey.DROP,
    )

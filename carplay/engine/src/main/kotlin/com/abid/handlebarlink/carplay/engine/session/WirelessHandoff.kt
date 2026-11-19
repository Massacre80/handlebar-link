package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the iAP2 link of one wireless attempt stands (DiPlay's handoff flags, CarPlayController.kt:337-343). */
internal data class HandoffState(
    val phase: Phase = Phase.BLUETOOTH,
    /** The iPhone sent `/command disableBluetooth`. */
    val requested: Boolean = false,
    val tunnel: Tunnel = Tunnel.NONE,
    /** The RFCOMM bootstrap is still running. */
    val bluetoothOpen: Boolean = false,
) {
    enum class Phase {
        /** iAP2 runs over Bluetooth (the tunnel may be coming up beside it). */
        BLUETOOTH,

        /** The tunnel took over and we closed the RFCOMM bootstrap (DiPlay `WirelessActive`). */
        BLUETOOTH_CLOSED,

        /** The iPhone asked for the hand-over but no tunnel was ready in time: Bluetooth stays (DiPlay's fallback). */
        FALLBACK,
    }

    enum class Tunnel {
        NONE,

        /** SETUP answered; the iPhone's connection, the link and identification are under way. */
        STARTING,

        /** Identified, authenticated and subscribed over the tunnel (`onReady`, Iap2WirelessControlClient.kt:61-63). */
        READY,

        /** The last tunnel ended. */
        ENDED,
    }
}

/**
 * The hand-over of iAP2 from the Bluetooth bootstrap to the AirPlay type-130 tunnel, as DiPlay runs it
 * (CarPlayController.kt:506-525, 2064-2101, 2164-2208; spec D §1.5): the iPhone opens the tunnel and sends
 * `/command disableBluetooth`; when both the request and a ready tunnel are there, the RFCOMM bootstrap is closed.
 * A request with no ready tunnel after [HANDOFF_TIMEOUT] keeps Bluetooth (DiPlay's fallback); a tunnel that becomes
 * ready later still takes over (`wirelessHandoffFellBack`, :2079-2083).
 *
 * Deliberate difference, the owner's choice (2026-11-19): DiPlay fails the whole connection when the tunnel fails or
 * the hand-over times out without a picture (:1934-1956, 2205-2208). Here a tunnel that fails while Bluetooth is still
 * open only falls back to Bluetooth, logged; DiPlay's strictness applies once we closed Bluetooth: then nothing else
 * carries iAP2, so the tunnel ending ends the attempt and the runtime reconnects ([endAttempt]).
 *
 * The calls (0x4155) come from whichever link is up; they are forgotten only when the last link carrying them ends.
 * One per attempt; touched only on the engine's dispatcher.
 */
internal class WirelessHandoff(
    private val scope: CoroutineScope,
    /** Where both links' CallStateUpdates go. */
    val calls: CallStates,
    private val time: TimeProvider,
    private val log: Logger,
    /** Ends the attempt (the tunnel was lost after Bluetooth was closed for it). */
    private val endAttempt: (CarPlayException) -> Unit,
) {
    private val flow = MutableStateFlow(HandoffState())
    private var closeBluetooth: (() -> Unit)? = null
    private var tunnelToken = 0
    private var requestedAt = 0L
    private var watchdog: Job? = null

    val state: StateFlow<HandoffState> = flow.asStateFlow()

    /** What the tunnel tells the iPhone, the same as the bootstrap; set before the bootstrap starts. */
    var setup: BootstrapSetup? = null

    /** The RFCOMM bootstrap runs; [close] stops it and closes the socket. */
    fun bluetoothOpened(close: () -> Unit) {
        closeBluetooth = close
        flow.update { it.copy(bluetoothOpen = true) }
    }

    /** The bootstrap ended (we closed it, the iPhone did, or the attempt ends). */
    fun bluetoothEnded() {
        closeBluetooth = null
        flow.update { it.copy(bluetoothOpen = false) }
        if (flow.value.tunnel != HandoffState.Tunnel.READY) calls.clear("bootstrap ended")
    }

    /** A tunnel SETUP was answered; returns the token its later reports carry (a newer tunnel replaces it). */
    fun tunnelStarting(): Int {
        tunnelToken++
        flow.update { it.copy(tunnel = HandoffState.Tunnel.STARTING) }
        return tunnelToken
    }

    /** The tunnel [token] is identified, authenticated and subscribed. */
    fun tunnelReady(token: Int) {
        if (token != tunnelToken) return
        flow.update { it.copy(tunnel = HandoffState.Tunnel.READY) }
        log.info("carplay.iap2.tunnel.ready", "handoffRequested" to flow.value.requested)
        maybeComplete()
    }

    /**
     * The tunnel [token] ended for [reason]. [sessionEnding]: its AirPlay session is closing anyway, which ends the
     * attempt by itself.
     */
    fun tunnelEnded(
        token: Int,
        reason: String,
        sessionEnding: Boolean,
    ) {
        if (token != tunnelToken) return
        val before = flow.value
        flow.update { it.copy(tunnel = HandoffState.Tunnel.ENDED) }
        log.info(
            "carplay.iap2.tunnel.ended",
            "reason" to reason,
            "phase" to before.phase.name,
            "sessionEnding" to sessionEnding,
        )
        if (!before.bluetoothOpen) calls.clear("tunnel ended")
        when {
            before.phase == HandoffState.Phase.BLUETOOTH_CLOSED && !sessionEnding -> {
                log.warn("carplay.handoff.tunnel_lost", "reason" to reason)
                endAttempt(
                    CarPlayException(
                        CarPlayError.PhoneDisconnected("iAP2 tunnel ended after the Bluetooth hand-over: $reason"),
                    ),
                )
            }

            before.phase == HandoffState.Phase.BLUETOOTH && before.requested -> {
                fallBack("tunnel ended: $reason")
            }

            else -> {}
        }
    }

    /** The iPhone's `/command disableBluetooth`, also `disable-bluetooth` in any case (CarPlayController.kt:2920). */
    fun disableBluetoothRequested() {
        if (flow.value.requested) return
        requestedAt = time.elapsedMillis()
        flow.update { it.copy(requested = true) }
        log.info("carplay.handoff.requested", "tunnel" to flow.value.tunnel.name)
        watchdog =
            scope.launch {
                delay(HANDOFF_TIMEOUT)
                if (flow.value.phase ==
                    HandoffState.Phase.BLUETOOTH
                ) {
                    fallBack("no tunnel ready after ${HANDOFF_TIMEOUT}ms")
                }
            }
        maybeComplete()
    }

    /** One owner closes Bluetooth, also when a fallback gains its tunnel later (CarPlayController.kt:2075-2101). */
    private fun maybeComplete() {
        val now = flow.value
        if (!now.requested || now.tunnel != HandoffState.Tunnel.READY ||
            now.phase == HandoffState.Phase.BLUETOOTH_CLOSED
        ) {
            return
        }
        watchdog?.cancel()
        flow.update { it.copy(phase = HandoffState.Phase.BLUETOOTH_CLOSED) }
        val close = closeBluetooth
        closeBluetooth = null
        close?.invoke()
        log.info(
            "carplay.handoff.bluetooth_closed",
            "after_request_ms" to time.elapsedMillis() - requestedAt,
            "was_open" to now.bluetoothOpen,
            "from" to now.phase.name,
        )
    }

    private fun fallBack(reason: String) {
        flow.update { it.copy(phase = HandoffState.Phase.FALLBACK) }
        log.warn(
            "carplay.handoff.fallback",
            "reason" to reason,
            "tunnel" to flow.value.tunnel.name,
            "bluetoothOpen" to flow.value.bluetoothOpen,
        )
    }

    companion object {
        /** DiPlay's WIRELESS_HANDOFF_TIMEOUT_MILLIS (CarPlayController.kt:3741). */
        const val HANDOFF_TIMEOUT = 45_000L

        /** DiPlay's `isBluetoothHandoffCommand` (CarPlayController.kt:2920-2922). */
        fun isHandoffCommand(type: String?): Boolean =
            type.equals("disableBluetooth", ignoreCase = true) || type.equals("disable-bluetooth", ignoreCase = true)
    }
}

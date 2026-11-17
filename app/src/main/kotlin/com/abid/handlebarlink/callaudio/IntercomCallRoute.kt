package com.abid.handlebarlink.callaudio

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import com.abid.handlebarlink.core.common.log.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Puts the phone into its call state while another app's call runs, so Android carries that call's sound
 * and microphone over the intercom's hands-free link instead of the phone's speaker and mic. The held call
 * has no audio of its own; Telecom only learns that a call is on (DiPlay's "car phone audio" option does the
 * same). Built on Core-Telecom (developer.android.com, "Core-Telecom"): the audio endpoint is chosen through
 * Telecom (`requestEndpointChange`), never with `setCommunicationDevice` or SCO calls, as the guide asks.
 */
class IntercomCallRoute
    @Inject
    constructor(
        @ApplicationContext context: Context,
        logger: Logger,
    ) {
        private val log = logger.child("callaudio")
        private val calls = CallsManager(context)
        private var registered = false

        /**
         * Holds one call until [until] returns, preferring a Bluetooth endpoint (the intercom) whenever one is
         * available. Failures are logged; the caller's call then simply stays on the phone.
         */
        @Suppress("TooGenericExceptionCaught") // Telecom reports a refused call as any exception; logged, not fatal.
        suspend fun hold(until: suspend () -> Unit) {
            try {
                val done = CompletableDeferred<Unit>()
                if (!registered) {
                    calls.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                    registered = true
                }
                calls.addCall(
                    ATTRIBUTES,
                    onAnswer = { log.info("callaudio.telecom.answer") },
                    onDisconnect = { log.info("callaudio.telecom.disconnect", "cause" to it.reason) },
                    onSetActive = { log.info("callaudio.telecom.active") },
                    onSetInactive = { log.info("callaudio.telecom.inactive") },
                ) {
                    var current: CallEndpointCompat? = null
                    launch {
                        currentCallEndpoint.collect {
                            current = it
                            log.info("callaudio.endpoint", "name" to it.name, "type" to it.type)
                        }
                    }
                    launch {
                        availableEndpoints.collect { endpoints ->
                            log.info("callaudio.endpoints", "available" to endpoints.map { "${it.name}:${it.type}" })
                            val intercom = endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
                            if (intercom != null && current?.type != CallEndpointCompat.TYPE_BLUETOOTH) {
                                val result = requestEndpointChange(intercom)
                                log.info(
                                    "callaudio.route",
                                    "to" to intercom.name,
                                    "ok" to (result is CallControlResult.Success),
                                )
                            }
                        }
                    }
                    launch {
                        log.info("callaudio.held", "active" to (setActive() is CallControlResult.Success))
                        try {
                            until()
                            disconnect(DisconnectCause(DisconnectCause.LOCAL))
                            log.info("callaudio.released")
                        } finally {
                            done.complete(Unit)
                        }
                    }
                }
                done.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("callaudio.refused", "error" to e.toString())
            }
        }

        private companion object {
            val ATTRIBUTES =
                CallAttributesCompat(
                    displayName = "CarPlay call",
                    address = Uri.fromParts("sip", "carplay@handlebarlink", null),
                    direction = CallAttributesCompat.DIRECTION_OUTGOING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    callCapabilities = 0,
                )
        }
    }

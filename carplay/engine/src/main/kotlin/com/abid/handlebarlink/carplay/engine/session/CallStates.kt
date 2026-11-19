package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayCall
import com.abid.handlebarlink.carplay.engine.CarPlayCallStatus
import com.abid.handlebarlink.carplay.protocol.iap2.CallStateUpdate
import com.abid.handlebarlink.carplay.protocol.iap2.Iap2Message
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The iPhone's calls as its CallStateUpdates (0x4155) describe them, kept like DiPlay's CarPlayCallState
 * (`BydCarPlayCall.kt:27-63`): one entry per call key (UUID, else remote id), in the order first reported; a
 * disconnected or disconnecting update removes the call. A status DiPlay does not know removes it too: DiPlay keeps
 * such a call but never shows it, which is the same for everything that reads [current]. Touched only on the engine's
 * dispatcher (the bootstrap's serve loop); [current] may be read anywhere.
 *
 * Logs carry statuses only, never the call key (it may be the caller's number).
 */
internal class CallStates(
    private val log: Logger,
) {
    private val calls = LinkedHashMap<String, CarPlayCallStatus>()
    private val flow = MutableStateFlow<List<CarPlayCall>>(emptyList())

    val current: StateFlow<List<CarPlayCall>> = flow.asStateFlow()

    /** Takes one CallStateUpdate; one without a usable status is logged and ignored. */
    fun accept(message: Iap2Message) {
        val update = CallStateUpdate.parse(message)
        if (update == null) {
            log.warn("carplay.calls.ignored", "parameters" to message.parameters.map { it.id })
            return
        }
        val status = CarPlayCallStatus.entries.firstOrNull { it.iap2 == update.status }
        if (status == null) {
            calls.remove(update.callKey)
            if (update.status !in ENDED) log.warn("carplay.calls.unknown_status", "status" to update.status)
        } else {
            calls[update.callKey] = status
        }
        publish(update.status)
    }

    /** Forgets every call: nothing reports them any more ([reason]: the bootstrap link or the attempt ended). */
    fun clear(reason: String) {
        if (calls.isEmpty() && flow.value.isEmpty()) return
        calls.clear()
        flow.value = emptyList()
        log.info("carplay.calls.cleared", "reason" to reason)
    }

    private fun publish(status: Int) {
        val next = calls.values.map { CarPlayCall(it) }
        val changed = next != flow.value
        flow.value = next
        log.info("carplay.calls.update", "status" to status, "calls" to next.map { it.status }, "changed" to changed)
    }

    private companion object {
        val ENDED = setOf(CallStateUpdate.DISCONNECTED, CallStateUpdate.DISCONNECTING)
    }
}

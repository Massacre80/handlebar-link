package com.abid.handlebarlink.carplay.protocol.iap2

/**
 * One iAP2 CallStateUpdate (0x4155): the iPhone reports one call's status. The receiver asks for them with
 * StartCallStateUpdates (0x4154, [WirelessCarPlayMessages.subscriptions]) and lists 0x4155 among the messages it
 * accepts at identification ([WirelessCarPlayMessages.MESSAGES_RECEIVED]).
 *
 * Parameters as DiPlay reads them (CarLink vendor/diplay `shared/.../hud/BydCarPlayCall.kt:22-24, 34-51, 78-81`):
 * 0 remote id (string), 1 display name (string), 2 status (u8), 4 call UUID (string). A call is known by its UUID,
 * or by its trimmed remote id when the UUID is missing or blank ([callKey], `BydCarPlayCall.kt:38-40`). The name
 * and the number are not kept: nothing here needs them, and they must never reach a log.
 */
public class CallStateUpdate(
    /** The call's status as sent: see [DISCONNECTED] … [DISCONNECTING]; other values are possible. */
    public val status: Int,
    /** What identifies the call across updates: its UUID, else its remote id (may be a phone number; never log it). */
    public val callKey: String,
) {
    /** Leaves [callKey] out on purpose: it may be the caller's number. */
    override fun toString(): String = "CallStateUpdate(status=$status)"

    /** Statuses and parsing. */
    public companion object {
        /** The message id (DiPlay `BydCarPlayCall.kt:77`, `iap2/catalog/Iap2Endpoint.kt:646`). */
        public const val MESSAGE_ID: Int = 0x4155

        /** The call is over (`BydCarPlayCall.kt:82`). */
        public const val DISCONNECTED: Int = 0

        /** An outgoing call is being placed (`BydCarPlayCall.kt:83`). */
        public const val SENDING: Int = 1

        /** An incoming call rings (`BydCarPlayCall.kt:84`). */
        public const val RINGING: Int = 2

        /** The call is connecting (`BydCarPlayCall.kt:85`). */
        public const val CONNECTING: Int = 3

        /** The call is on (`BydCarPlayCall.kt:86`). */
        public const val ACTIVE: Int = 4

        /** The call is on hold (`BydCarPlayCall.kt:87`). */
        public const val HELD: Int = 5

        /** The call is ending (`BydCarPlayCall.kt:88`); DiPlay drops it at once, as [DISCONNECTED]. */
        public const val DISCONNECTING: Int = 6

        private const val REMOTE_ID = 0
        private const val STATUS = 2
        private const val CALL_UUID = 4

        /**
         * The update in [message], or null when it is not a CallStateUpdate or carries no usable status, as DiPlay
         * (`BydCarPlayCall.kt:35-37`: a missing status, or one that is not exactly one byte, ignores the update). A
         * remote id or UUID that is not a proper string counts as absent (DiPlay's `runCatching`, `:38-40`).
         */
        public fun parse(message: Iap2Message): CallStateUpdate? {
            val status =
                message.parameter(STATUS)?.takeIf { message.id == MESSAGE_ID && it.payload.size == 1 }?.u8()
                    ?: return null
            val remote = message.optionalString(REMOTE_ID)?.trim().orEmpty()
            val uuid = message.optionalString(CALL_UUID)?.takeIf { it.isNotBlank() }
            return CallStateUpdate(status, uuid ?: remote)
        }

        private fun Iap2Message.optionalString(id: Int): String? =
            try {
                parameter(id)?.string()
            } catch (_: Iap2ProtocolException) {
                null
            }
    }
}

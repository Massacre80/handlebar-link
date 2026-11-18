package com.abid.handlebarlink.carplay.protocol.iap2

import java.io.ByteArrayOutputStream

/**
 * Builds the frames we send and collects their bytes until the owner writes them to the socket.
 * Every frame carries the last in-order sequence received; writing one with the ACK bit settles the
 * pending acknowledgement (DiPlay Iap2LinkEngine.kt:518-554).
 */
internal class LinkWriter(
    private val receive: ReceiveWindow,
) {
    private val output = ByteArrayOutputStream()

    /** The last sequence we acknowledged to the peer. */
    var acknowledged: Int = 0
        private set

    /** When a delayed acknowledgement is due, or null. */
    var acknowledgeDue: Long? = null

    val hasOutput: Boolean get() = output.size() > 0

    fun marker() {
        output.write(Iap2Frame.MARKER)
    }

    fun synchronize(
        sequence: Int,
        parameters: Iap2LinkParameters,
    ) = write(Iap2Frame.SYN, sequence, sessionId = 0, payload = parameters.encode())

    fun acknowledge(lastSent: Int) = write(Iap2Frame.ACK, lastSent, sessionId = 0, payload = null)

    /**
     * EAK with the missing sequences; DiPlay sends it without the ACK bit (spec A §8.11) and drops the pending
     * delayed acknowledgement with it (Iap2LinkEngine.kt:447-448).
     */
    fun extendedAcknowledge(
        lastSent: Int,
        missing: List<Int>,
    ) {
        acknowledgeDue = null
        write(Iap2Frame.EAK, lastSent, sessionId = 0, payload = ByteArray(missing.size) { missing[it].toByte() })
    }

    fun data(packet: SendWindow.Packet) = write(Iap2Frame.ACK, packet.sequence, packet.sessionId, packet.payload)

    fun drain(): ByteArray = output.toByteArray().also { output.reset() }

    private fun write(
        control: Int,
        sequence: Int,
        sessionId: Int,
        payload: ByteArray?,
    ) {
        val frame = Iap2Frame(control, sequence, receive.lastInOrder, sessionId, payload)
        output.write(frame.encode())
        if (frame.hasAcknowledgement) {
            acknowledged = receive.lastInOrder
            acknowledgeDue = null
        }
    }
}

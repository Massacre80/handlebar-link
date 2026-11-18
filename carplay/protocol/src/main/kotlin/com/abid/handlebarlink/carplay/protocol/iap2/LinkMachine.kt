package com.abid.handlebarlink.carplay.protocol.iap2

/**
 * The link's reaction to each received frame: handshake, acknowledgements, retransmission and in-order
 * delivery (DiPlay Iap2LinkEngine.kt:327-474, spec A §5). Deliberate differences from DiPlay, each a
 * robustness fix from spec A §8: an ACK before the iPhone's SYN is ignored rather than fatal (8.2); the
 * delayed acknowledgement is armed once, not pushed back by every packet (8.12), and fires after
 * `maxCumulativeAcknowledgements` packets as the parameter's name says; an EAK resend gets a fresh deadline (8.10).
 */
internal class LinkMachine(
    private val local: Iap2LinkParameters,
) {
    var state: Iap2Link.State = Iap2Link.State.IDLE
    var peer: Iap2LinkParameters? = null
        private set
    var synchronizeDue: Long? = null
    var markerDue: Long? = null

    val receive = ReceiveWindow()
    val send = SendWindow(INITIAL_SEQUENCE)
    val writer = LinkWriter(receive)
    val events = ArrayList<Iap2LinkEvent>()

    /** The iPhone's SYN, kept to answer a repeat of it until the first data arrives (DiPlay issue #1220). */
    private var peerSynchronize: Iap2Frame? = null

    fun sendSynchronize(now: Long) {
        writer.synchronize(INITIAL_SEQUENCE, local)
        synchronizeDue = now + SYNCHRONIZE_RESEND_MILLIS
    }

    fun onFrame(
        frame: Iap2Frame,
        now: Long,
    ) {
        if (frame.isReset) throw Iap2LinkException("iPhone reset the iAP2 link")
        if (frame.isSynchronize) onSynchronize(frame)
        if (frame.hasAcknowledgement) onAcknowledgement(frame, now)
        val payload = frame.payload
        if (frame.isExtendedAcknowledgement && state == Iap2Link.State.CONNECTED && payload != null) {
            val missing = payload.map { it.toInt() and 0xFF }.toSet()
            send.withSequences(missing).forEach { retransmit(it, now) }
        }
        if (frame.isData && state == Iap2Link.State.CONNECTED) onData(frame, now)
    }

    fun retransmit(
        packet: SendWindow.Packet,
        now: Long,
    ) {
        val limits = requireNotNull(peer)
        if (packet.retransmissions >= limits.maxRetransmissions) {
            throw Iap2LinkException(
                "iPhone did not acknowledge packet ${packet.sequence} after ${packet.retransmissions} retransmissions",
            )
        }
        packet.retransmissions++
        packet.deadline = now + limits.retransmitMillis
        writer.data(packet)
    }

    /** Sends waiting packets while the iPhone's window has room. */
    fun flush(now: Long) {
        val limits = peer ?: return
        if (state != Iap2Link.State.CONNECTED) return
        val released =
            send.release(
                limits.maxOutgoing,
                now + limits.retransmitMillis,
                track =
                    limits.maxRetransmissions > 0,
            )
        released.forEach {
            if (it.payload.size > limits.maxPayloadBytes) {
                throw Iap2LinkException(
                    "Payload of ${it.payload.size} bytes exceeds the iPhone's ${limits.maxPayloadBytes}",
                )
            }
            writer.data(it)
        }
    }

    private fun onSynchronize(frame: Iap2Frame) {
        when (state) {
            Iap2Link.State.NEGOTIATING -> {
                val parameters = frame.payload?.let(Iap2LinkParameters::decode) ?: return
                if (parameters.maxPayloadBytes <= 0) throw Iap2LinkException("iPhone's iAP2 frames cannot carry data")
                peer = parameters
                peerSynchronize = frame
                receive.reset(frame.sequence)
                writer.acknowledge(send.lastSent)
            }

            Iap2Link.State.CONNECTED -> {
                val first = peerSynchronize ?: return
                val repeat = frame.sequence == first.sequence && frame.payload.contentEquals(first.payload)
                if (repeat && frame.hasAcknowledgement) writer.acknowledge(send.lastSent)
            }

            else -> {}
        }
    }

    private fun onAcknowledgement(
        frame: Iap2Frame,
        now: Long,
    ) {
        when (state) {
            Iap2Link.State.NEGOTIATING -> {
                val parameters = peer ?: return
                state = Iap2Link.State.CONNECTED
                synchronizeDue = null
                events += Iap2LinkEvent.Connected(parameters)
                flush(now)
            }

            Iap2Link.State.CONNECTED -> {
                send.acknowledge(frame.acknowledgement)
                flush(now)
            }

            else -> {}
        }
    }

    private fun onData(
        frame: Iap2Frame,
        now: Long,
    ) {
        val limits = requireNotNull(peer)
        when (val result = receive.accept(frame, limits.maxOutgoing)) {
            ReceiveWindow.Result.Duplicate -> {
                writer.acknowledge(send.lastSent)
            }

            ReceiveWindow.Result.Buffered -> {}

            is ReceiveWindow.Result.Gap -> {
                writer.extendedAcknowledge(send.lastSent, result.missing)
            }

            is ReceiveWindow.Result.Delivered -> {
                result.frames.forEach { events += Iap2LinkEvent.Message(it.sessionId, requireNotNull(it.payload)) }
                peerSynchronize = null
                scheduleAcknowledge(limits, now)
            }
        }
    }

    private fun scheduleAcknowledge(
        limits: Iap2LinkParameters,
        now: Long,
    ) {
        // A peer that wants no acknowledgements gets none (DiPlay Iap2LinkEngine.kt:465-466).
        if (limits.maxCumulativeAcknowledgements == 0) return
        val pending = sequenceDistance(receive.lastInOrder, writer.acknowledged)
        if (pending >= limits.maxCumulativeAcknowledgements) {
            writer.acknowledge(send.lastSent)
        } else if (writer.acknowledgeDue == null) {
            writer.acknowledgeDue = now + limits.acknowledgeMillis
        }
    }

    companion object {
        /** DiPlay's first sequence number (Iap2LinkEngine.kt:679); the first data packet is 100. */
        const val INITIAL_SEQUENCE = 99
        const val MARKER_RESEND_MILLIS = 1_000L
        const val SYNCHRONIZE_RESEND_MILLIS = 500L
    }
}

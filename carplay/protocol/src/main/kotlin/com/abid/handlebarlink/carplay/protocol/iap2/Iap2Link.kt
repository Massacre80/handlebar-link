package com.abid.handlebarlink.carplay.protocol.iap2

/** What the link reports to its owner. */
public sealed interface Iap2LinkEvent {
    /** The handshake finished; [peer] holds the iPhone's limits, which govern both directions. */
    public data class Connected(
        /** The iPhone's link parameters. */
        val peer: Iap2LinkParameters,
    ) : Iap2LinkEvent

    /** A data payload for [sessionId], in order. */
    public class Message(
        /** Link session id (10 control, 11 external accessory, 12 file transfer). */
        public val sessionId: Int,
        /** The payload bytes. */
        public val payload: ByteArray,
    ) : Iap2LinkEvent
}

/**
 * The iAP2 link layer over the RFCOMM stream, as a pure state machine: bytes and time go in, bytes to
 * write and events come out. No threads, no clock; the owner calls [onTimer] at [nextDeadline]. Not
 * thread-safe: one owner, one thread (the engine's dispatcher).
 *
 * Wireless handshake (DiPlay, spec A §2, §5.3): we send the marker every second until the iPhone echoes
 * it, then our SYN every 500 ms until the iPhone's SYN+ACK, which we acknowledge.
 *
 * Any [Iap2LinkException] leaves the link [State.FAILED]; open a new one for the next connection.
 */
public class Iap2Link(
    local: Iap2LinkParameters = Iap2LinkParameters.WIRELESS,
) {
    /** The link's phase. */
    public enum class State {
        /** Not started. */
        IDLE,

        /** Sending the marker, waiting for the iPhone's. */
        DETECTING,

        /** Sending our SYN, waiting for the iPhone's SYN+ACK. */
        NEGOTIATING,

        /** Data flows. */
        CONNECTED,

        /** Dead after an error. */
        FAILED,
    }

    private val machine = LinkMachine(local)
    private val reader = FrameReader()

    /** The current phase. */
    public val state: State get() = machine.state

    /** The largest payload the iPhone accepts in one frame; null before [Iap2LinkEvent.Connected]. */
    public val maxPayloadBytes: Int? get() = machine.peer?.maxPayloadBytes

    /** Starts detection: queues the marker. */
    public fun start(now: Long) {
        check(machine.state == State.IDLE) { "iAP2 link already started" }
        machine.state = State.DETECTING
        machine.writer.marker()
        machine.markerDue = now + LinkMachine.MARKER_RESEND_MILLIS
    }

    /**
     * Feeds bytes read from the socket.
     *
     * @throws Iap2LinkException on a protocol failure; the link is then [State.FAILED].
     */
    public fun receive(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        now: Long,
    ): Unit =
        guarded {
            if (machine.state == State.IDLE || machine.state == State.FAILED) return@guarded
            reader.append(bytes, offset, length)
            if (machine.state == State.DETECTING) {
                if (!reader.takeMarker()) return@guarded
                machine.markerDue = null
                machine.state = State.NEGOTIATING
                machine.sendSynchronize(now)
            }
            while (true) machine.onFrame(reader.nextFrame() ?: break, now)
        }

    /**
     * Queues [payload] for [sessionId]; it goes out as soon as the link is connected and the window has
     * room. The payload must fit one frame ([maxPayloadBytes]); the control session splits larger messages.
     *
     * @throws Iap2LinkException when the send queue is full or the link has failed.
     */
    public fun send(
        sessionId: Int,
        payload: ByteArray,
        now: Long,
    ): Unit =
        guarded {
            if (machine.state == State.FAILED) throw Iap2LinkException("iAP2 link has failed")
            check(machine.state != State.IDLE) { "iAP2 link is not started" }
            require(payload.isNotEmpty()) { "Empty iAP2 payload" }
            machine.send.enqueue(sessionId, payload)
            machine.flush(now)
        }

    /**
     * Runs whatever is due at [now]: marker and SYN resends, the delayed acknowledgement, retransmissions.
     *
     * @throws Iap2LinkException when a packet ran out of retransmissions.
     */
    public fun onTimer(now: Long): Unit =
        guarded {
            // A failed link is silent, as DiPlay's die() clears every timer (Iap2LinkEngine.kt:562-573).
            if (machine.state == State.FAILED) return@guarded
            machine.markerDue?.takeIf { now >= it }?.let {
                machine.writer.marker()
                machine.markerDue = now + LinkMachine.MARKER_RESEND_MILLIS
            }
            machine.synchronizeDue?.takeIf { now >= it }?.let { machine.sendSynchronize(now) }
            machine.writer.acknowledgeDue?.takeIf { now >= it }?.let {
                machine.writer.acknowledge(
                    machine.send.lastSent,
                )
            }
            if (machine.state == State.CONNECTED) machine.send.due(now).forEach { machine.retransmit(it, now) }
        }

    /** When [onTimer] must next run, or null when nothing is scheduled. */
    public fun nextDeadline(): Long? =
        if (machine.state == State.FAILED) {
            null
        } else {
            deadlines()
        }

    private fun deadlines(): Long? =
        listOfNotNull(
            machine.markerDue,
            machine.synchronizeDue,
            machine.writer.acknowledgeDue,
            machine.send.nextDeadline.takeIf { machine.state == State.CONNECTED },
        ).minOrNull()

    /** Bytes to write to the socket since the last call; empty when there are none. */
    public fun drainOutput(): ByteArray = machine.writer.drain()

    /** Events since the last call, in order. */
    public fun drainEvents(): List<Iap2LinkEvent> = machine.events.toList().also { machine.events.clear() }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Iap2LinkException) {
            machine.state = State.FAILED
            throw e
        }
    }
}

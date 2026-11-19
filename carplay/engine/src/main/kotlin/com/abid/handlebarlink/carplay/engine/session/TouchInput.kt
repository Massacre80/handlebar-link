package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.TouchContact
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * The rider's touches on their way to the iPhone ([com.abid.handlebarlink.carplay.engine.CarPlayLink.touch]). [offer]
 * only queues (any thread, never blocks); the session that holds the event channel [serve]s the queue. The queue is
 * bounded and drops the oldest touch when full: every touch carries all fingers, so the newest one, which the slots
 * need most (a release), is always kept.
 */
internal class TouchInput {
    private val queue = Channel<List<TouchContact>>(CAPACITY, BufferOverflow.DROP_OLDEST)

    fun offer(contacts: List<TouchContact>) {
        queue.trySend(contacts.toList())
    }

    /**
     * Sends each queued touch to [events] as a touchscreen report on a [width] x [height] canvas, until cancelled.
     * Touches that waited for this event channel are dropped first: a tap made before the session must not land in
     * it. Each event channel gets its own slots (DiPlay keeps them per AirPlay session, AirPlaySession.kt:114).
     */
    suspend fun serve(
        events: EventCommands,
        width: Int,
        height: Int,
        log: Logger,
    ) {
        var stale = 0
        while (queue.tryReceive().isSuccess) stale++
        if (stale > 0) log.info("carplay.touch.stale_dropped", "touches" to stale)
        val slots = TouchSlots(HidDevices.TOUCH_CONTACTS)
        val counts = Counts()
        try {
            for (contacts in queue) {
                val report = HidDevices.touchReport(slots.assign(contacts, width, height))
                counts.count(events.send(HidDevices.touchCommand(report)), contacts.size, log)
            }
        } finally {
            log.info("carplay.touch.ended", "sent" to counts.sent, "failed" to counts.failed)
        }
    }

    /** Touch reports sent and failed on one event channel; the first of each is logged. */
    private class Counts {
        var sent = 0L
        var failed = 0L

        fun count(
            delivered: Boolean,
            contacts: Int,
            log: Logger,
        ) {
            if (delivered) {
                if (sent++ == 0L) log.info("carplay.touch.first", "contacts" to contacts)
            } else if (failed++ == 0L) {
                log.warn("carplay.touch.dropped", "reason" to "event channel not ready")
            }
        }
    }

    private companion object {
        /** About two seconds of a 60 Hz touch stream; a session only falls this far behind when it is stuck. */
        const val CAPACITY = 128
    }
}

/**
 * Keeps each finger in one touch report slot from press to release (DiPlay AirPlayTouchSlots.kt:9-35, #444).
 * Contacts arrive with pointer ids, which stay with a finger while the others' indexes change. A finger that is
 * gone, and every finger when the list is empty, is released where it last was: a release at (0,0) would drag it to
 * the corner first. A finger beyond the free slots is left out. Positions are scaled to the canvas as DiPlay's
 * `sendTouch` does (AirPlaySession.kt:352-354).
 */
internal class TouchSlots(
    private val slots: Int,
) {
    private val pointers = arrayOfNulls<Int>(slots)
    private val held = arrayOfNulls<HidDevices.Contact>(slots)

    /** The contacts to report, each in its slot, in canvas pixels; empty slots are left out. */
    fun assign(
        contacts: List<TouchContact>,
        width: Int,
        height: Int,
    ): List<HidDevices.Contact> {
        val report = arrayOfNulls<HidDevices.Contact>(slots)
        for (contact in contacts) {
            val slot =
                pointers.indexOf(contact.pointerId).takeIf { it >= 0 }
                    ?: pointers.indexOf(null).takeIf { it >= 0 && contact.down }
                    ?: continue
            pointers[slot] = contact.pointerId
            report[slot] =
                HidDevices.Contact(
                    slot,
                    contact.x.coerceIn(0.0, 1.0) * width,
                    contact.y.coerceIn(0.0, 1.0) * height,
                    contact.down,
                )
        }
        for (slot in 0 until slots) {
            if (report[slot] == null) report[slot] = held[slot]?.let { HidDevices.Contact(slot, it.x, it.y, false) }
            val sent = report[slot]
            if (sent?.down == true) {
                held[slot] = sent
            } else {
                held[slot] = null
                pointers[slot] = null
            }
        }
        return report.filterNotNull()
    }
}

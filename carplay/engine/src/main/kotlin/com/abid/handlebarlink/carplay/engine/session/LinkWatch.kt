package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.StallVerdict
import com.abid.handlebarlink.carplay.engine.spi.GroupClients
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spots a wireless start that hangs after the iPhone got the Wi-Fi details, and an iPhone that left a running
 * session, in seconds instead of a 30 s step timeout. Ported from CarLink's CarPlayLinkWatch.kt:15-110 (its
 * thresholds come from its tablet + iPhone 15 measurements, quoted there); times are elapsed milliseconds.
 *
 * One deliberate difference: [Verdict.Stall] NO_TCP counts from the latest StartSession, not the first, so the
 * StartSession nudges (E-connection.md #3) get their full time before the attempt is given up. And nothing is
 * decided before the platform has reported the group's clients once: a platform that cannot report them must
 * never make a healthy start look like an iPhone that did not join.
 */
internal class LinkWatch {
    sealed interface Verdict {
        data class Stall(
            val verdict: StallVerdict,
        ) : Verdict

        /** A verified session's iPhone left the group and did not come back within [PHONE_GONE]. */
        data object PhoneLeft : Verdict
    }

    private var wifiOfferedAt: Long? = null
    private var startSessionAt: Long? = null
    private var joinedAt: Long? = null
    private var leasedAt: Long? = null
    private var tcp = false
    private var connected = false
    private var hadClient = false
    private var goneSince: Long? = null
    private var reported = false

    /** The platform has reported the group's clients at least once. */
    private var observed = false

    fun wifiOffered(now: Long) {
        if (wifiOfferedAt == null) wifiOfferedAt = now
    }

    fun startSessionSent(now: Long) {
        startSessionAt = now
    }

    fun tcpAccepted() {
        tcp = true
    }

    /** The AirPlay connection is verified: from now on only [Verdict.PhoneLeft] applies. */
    fun connected() {
        connected = true
    }

    fun clients(
        clients: GroupClients,
        now: Long,
    ) {
        observed = true
        if (clients.joined > 0) {
            hadClient = true
            if (joinedAt == null) joinedAt = now
            goneSince = null
        } else if (hadClient && goneSince == null) {
            goneSince = now
        }
        if (clients.leased > 0 && leasedAt == null) leasedAt = now
    }

    /** What is wrong at [now], at most once; null while all is well or undecided. */
    fun verdict(now: Long): Verdict? {
        val due = if (reported) null else deadline()?.takeIf { now >= it.first }
        if (due != null) reported = true
        return due?.second
    }

    /** When the next verdict would be due if nothing changes, or null when none can come without a change. */
    fun nextDeadline(): Long? = if (reported) null else deadline()?.first

    private fun deadline(): Pair<Long, Verdict>? {
        val offered = wifiOfferedAt
        val joined = joinedAt
        val start = startSessionAt
        val leased = leasedAt
        return when {
            !observed -> null
            connected -> goneSince?.let { it + PHONE_GONE to Verdict.PhoneLeft }
            tcp || offered == null -> null
            joined == null -> offered + NOT_JOINED to Verdict.Stall(StallVerdict.NOT_JOINED)
            start == null -> joined + NO_START to Verdict.Stall(StallVerdict.NO_START_REQUEST)
            leased == null -> null
            else -> maxOf(start, leased) + NO_TCP to Verdict.Stall(StallVerdict.NO_TCP)
        }
    }

    companion object {
        /** StartSession followed the Wi-Fi details within 0.25 s on every good start; counted from the join. */
        const val NO_START = 4_000L

        /** The iPhone joined within 5.1 s of the Wi-Fi details (a never-seen network: 3.3 s). */
        const val NOT_JOINED = 9_000L

        /** TCP came within 2.8 s of the lease on a known network; a never-seen one needs longer. */
        const val NO_TCP = 8_000L

        /** A roam or radio hiccup rejoins within this; an iPhone that turned Wi-Fi off does not. */
        const val PHONE_GONE = 2_000L
    }
}

/**
 * Runs a [LinkWatch] inside one attempt: feeds it the platform's client reports and the attempt's milestones, and
 * fails the attempt with its verdict as soon as one is due. Every input wakes the waiting loop through a conflated
 * channel that the loop never signals itself (docs/LESSONS.md 49).
 */
internal class LinkWatcher(
    private val clientReports: Flow<GroupClients>,
    private val time: TimeProvider,
    private val log: Logger,
) {
    private val watch = LinkWatch()
    private val changed = Channel<Unit>(Channel.CONFLATED)

    fun wifiOffered() = update { watch.wifiOffered(time.elapsedMillis()) }

    fun startSessionSent() = update { watch.startSessionSent(time.elapsedMillis()) }

    fun tcpAccepted() = update { watch.tcpAccepted() }

    fun connected() = update { watch.connected() }

    /** The group's clients feed the watch; a lost group fails the attempt (the flow throws). */
    suspend fun followClients() {
        clientReports.collect { clients ->
            log.info("carplay.wifi.clients", "joined" to clients.joined, "leased" to clients.leased)
            update { watch.clients(clients, time.elapsedMillis()) }
        }
    }

    /** Throws the watch's verdict as a [CarPlayException] once it is due; runs until then. */
    suspend fun run(): Nothing {
        while (true) {
            val now = time.elapsedMillis()
            watch.verdict(now)?.let { throw failure(it) }
            val deadline = watch.nextDeadline()
            if (deadline == null || deadline <= now) {
                changed.receive()
            } else {
                withTimeoutOrNull(deadline - now) { changed.receive() }
            }
        }
    }

    private fun failure(verdict: LinkWatch.Verdict): CarPlayException {
        log.warn("carplay.watch.verdict", "verdict" to verdict)
        return when (verdict) {
            is LinkWatch.Verdict.Stall -> CarPlayException(CarPlayError.Stalled(verdict.verdict))
            LinkWatch.Verdict.PhoneLeft -> CarPlayException(CarPlayError.PhoneDisconnected(PHONE_LEFT))
        }
    }

    private inline fun update(change: () -> Unit) {
        change()
        changed.trySend(Unit)
    }

    companion object {
        const val PHONE_LEFT = "iPhone left the Wi-Fi"
    }
}

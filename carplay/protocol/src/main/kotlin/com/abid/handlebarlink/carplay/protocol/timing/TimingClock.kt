package com.abid.handlebarlink.carplay.protocol.timing

import java.math.BigInteger

/**
 * The receiver's side of the timing port, as DiPlay's NtpClock.kt (spec D §2.8): a 32-byte RTCP-style
 * exchange over UDP that answers the iPhone's requests and steers a local clock onto the iPhone's clock,
 * which `/feedback` and buffered audio's anchors report ([now]).
 *
 * Packet (NtpClock.kt:87-97, 124-133, 184-190): byte 0 `0x80`, byte 1 payload type [REQUEST] (210) or
 * [RESPONSE] (211), bytes 2..3 `00 07`, then three NTP64 big-endian stamps: originate at 8, receive at 16,
 * transmit at 24.
 *
 * - Requests from anyone are answered ([answer]; NtpClock.kt:117-133).
 * - We send [request] every [REQUEST_INTERVAL_MILLIS] to the `timingPort` from the session SETUP, when it
 *   is above 0 (NtpClock.kt:49-53, 70-97; AirPlaySession.kt:717, 856-857).
 * - Responses count only from the iPhone (the caller passes `fromPeer`) and only when they echo our last
 *   request's transmit time (NtpClock.kt:117-145). Of every [PICK_COUNT] the one with the shortest round
 *   trip is picked; it is applied when its round trip is no longer than any of the last [DELAY_WINDOW]
 *   picks. The first applied offset, and any above [STEP_THRESHOLD_SECONDS], steps the clock; smaller ones
 *   slew it by [SLEW_GAIN] (NtpClock.kt:147-180).
 *
 * Time is injected: [monotonicNanos] (a `System.nanoTime` equivalent) and the wall clock once, at
 * construction, as the starting point (NtpClock.kt:30, 205-218). Thread-safe: the receive loop, the
 * request timer and readers of [now] may run on different threads.
 */
public class TimingClock(
    private val monotonicNanos: () -> Long,
    wallClockMillis: Long,
) {
    private val lock = Any()
    private var clockOffsetNs = wallClockOffsetNs(wallClockMillis, monotonicNanos())
    private val delays = DoubleArray(DELAY_WINDOW) { Double.POSITIVE_INFINITY }
    private var delayIndex = 0
    private var pickCount = PICK_COUNT
    private var pickRtt = Double.POSITIVE_INFINITY
    private var pickOffset = 0.0
    private var pendingT1: Long? = null
    private var synced = false

    /** Whether an offset from the iPhone has been applied. */
    public val isSynced: Boolean get() = synchronized(lock) { synced }

    /**
     * Now on the iPhone's clock: an NTP64 value (seconds since 1900 in the high 32 bits) whose 64 bits are
     * unsigned; [unsigned] gives it as a plist integer would carry it (NtpClock.kt:55-58).
     */
    public fun now(): Long = synchronized(lock) { ntpFromNanos(monotonicNanos() + clockOffsetNs) }

    /** A request to send to the iPhone's timing port; its transmit time is remembered (NtpClock.kt:87-97). */
    public fun request(): ByteArray {
        val packet = header(REQUEST)
        synchronized(lock) {
            val t1 = ntpFromNanos(monotonicNanos() + clockOffsetNs)
            pendingT1 = t1
            putLong(packet, TRANSMIT_OFFSET, t1)
        }
        return packet
    }

    /**
     * Handles one received datagram of [length] bytes. Returns the response to send back to its sender for a
     * request, null otherwise. A response steers the clock only when [fromPeer] (its source address is the
     * iPhone's, NtpClock.kt:120). Datagrams shorter than [PACKET_BYTES] and other types are ignored
     * (NtpClock.kt:111, 118-121).
     */
    public fun answer(
        packet: ByteArray,
        length: Int = packet.size,
        fromPeer: Boolean,
    ): ByteArray? {
        require(length in 0..packet.size) { "Length out of range" }
        if (length < PACKET_BYTES) return null
        return when (packet[1].toInt() and 0xFF) {
            REQUEST -> {
                response(packet, receiveNtp = now(), transmitNtp = now())
            }

            RESPONSE -> {
                if (fromPeer) onResponse(packet)
                null
            }

            else -> {
                null
            }
        }
    }

    private fun onResponse(response: ByteArray) {
        synchronized(lock) {
            val t4 = unsigned(ntpFromNanos(monotonicNanos() + clockOffsetNs))
            val t1Bits = getLong(response, ORIGINATE_OFFSET)
            val pending = pendingT1
            if (pending == null || pending != t1Bits) return
            pendingT1 = null
            val t1 = unsigned(t1Bits)
            val t2 = unsigned(getLong(response, RECEIVE_OFFSET))
            val t3 = unsigned(getLong(response, TRANSMIT_OFFSET))
            val offset = (t2 - t1 + (t3 - t4)).toDouble() * HALF / TWO32
            val rtt = (t4 - t1 - (t3 - t2)).toDouble() / TWO32
            if (rtt >= 0.0) sample(offset, rtt)
        }
    }

    /** NtpClock.kt:151-167; called holding [lock]. */
    private fun sample(
        offset: Double,
        rtt: Double,
    ) {
        if (rtt < pickRtt) {
            pickRtt = rtt
            pickOffset = offset
        }
        if (--pickCount > 0) return
        val selectedRtt = pickRtt
        pickCount = PICK_COUNT
        pickRtt = Double.POSITIVE_INFINITY
        val useSample = delays.all { selectedRtt <= it }
        delays[delayIndex] = selectedRtt
        delayIndex = (delayIndex + 1) % DELAY_WINDOW
        if (useSample) applyOffset(pickOffset)
    }

    private fun applyOffset(offsetSeconds: Double) {
        val stepping = !synced || Math.abs(offsetSeconds) > STEP_THRESHOLD_SECONDS
        val applied = if (stepping) offsetSeconds else offsetSeconds * SLEW_GAIN
        clockOffsetNs += Math.round(applied * NANOS_PER_SECOND)
        if (stepping) {
            delays.fill(Double.POSITIVE_INFINITY)
            delayIndex = 0
            pendingT1 = null
            synced = true
        }
    }

    /** Packet layout and filter constants (NtpClock.kt:184-198). */
    public companion object {
        /** Payload type of a request (NtpClock.kt:185). */
        public const val REQUEST: Int = 210

        /** Payload type of a response (NtpClock.kt:186). */
        public const val RESPONSE: Int = 211

        /** Packet length (NtpClock.kt:187). */
        public const val PACKET_BYTES: Int = 32

        /** How often we send a request (NtpClock.kt:191). */
        public const val REQUEST_INTERVAL_MILLIS: Long = 1_000L

        /** DiPlay's receive buffer for one timing datagram (NtpClock.kt:192). */
        public const val RECEIVE_BUFFER_BYTES: Int = 2_048

        /** Offsets above this step the clock instead of slewing it (NtpClock.kt:193). */
        public const val STEP_THRESHOLD_SECONDS: Double = 0.128

        /** Share of a small offset applied per sample (NtpClock.kt:194). */
        public const val SLEW_GAIN: Double = 1.0 / 8.0

        /** Round trips remembered by the delay filter (NtpClock.kt:195). */
        public const val DELAY_WINDOW: Int = 8

        /** Responses per pick (NtpClock.kt:196). */
        public const val PICK_COUNT: Int = 2

        private const val ORIGINATE_OFFSET = 8
        private const val RECEIVE_OFFSET = 16
        private const val TRANSMIT_OFFSET = 24
        private const val NTP_EPOCH_OFFSET = 2_208_988_800L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val TWO32 = 4_294_967_296.0
        private const val HALF = 0.5
        private val MASK_64 = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE

        /** The 64 bits of an NTP64 value as the unsigned number they stand for. */
        public fun unsigned(ntp: Long): BigInteger = BigInteger.valueOf(ntp).and(MASK_64)

        /**
         * The response to [request] (NtpClock.kt:124-133): its transmit stamp as our originate, then
         * [receiveNtp] and [transmitNtp]. Null when [request] is not a full request.
         */
        public fun response(
            request: ByteArray,
            receiveNtp: Long,
            transmitNtp: Long,
        ): ByteArray? {
            if (request.size < PACKET_BYTES || (request[1].toInt() and 0xFF) != REQUEST) return null
            val out = header(RESPONSE)
            request.copyInto(out, ORIGINATE_OFFSET, TRANSMIT_OFFSET, PACKET_BYTES)
            putLong(out, RECEIVE_OFFSET, receiveNtp)
            putLong(out, TRANSMIT_OFFSET, transmitNtp)
            return out
        }

        private fun header(type: Int): ByteArray =
            ByteArray(PACKET_BYTES).also {
                it[0] = 0x80.toByte()
                it[1] = type.toByte()
                it[2] = 0
                it[3] = 7
            }

        /** NtpClock.kt:205-218: the wall clock as NTP64 (in doubles, as DiPlay), in ns, less [monoNs]. */
        private fun wallClockOffsetNs(
            wallClockMillis: Long,
            monoNs: Long,
        ): Long {
            val seconds = wallClockMillis / 1000.0 + NTP_EPOCH_OFFSET
            val whole = Math.floor(seconds).toLong()
            val fraction = Math.floor((seconds - whole) * TWO32).toLong()
            val ntp =
                BigInteger
                    .valueOf(whole)
                    .shiftLeft(32)
                    .or(BigInteger.valueOf(fraction))
                    .and(MASK_64)
            return ntp.multiply(BigInteger.valueOf(NANOS_PER_SECOND)).shiftRight(32).toLong() - monoNs
        }

        /** NtpClock.kt:220-224, its 64 bits. */
        private fun ntpFromNanos(ns: Long): Long {
            val seconds = Math.floorDiv(ns, NANOS_PER_SECOND)
            val nanos = Math.floorMod(ns, NANOS_PER_SECOND)
            return (seconds shl 32) or ((nanos shl 32) / NANOS_PER_SECOND)
        }

        private fun putLong(
            target: ByteArray,
            offset: Int,
            value: Long,
        ) {
            for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
        }

        private fun getLong(
            source: ByteArray,
            offset: Int,
        ): Long {
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
            return value
        }
    }
}

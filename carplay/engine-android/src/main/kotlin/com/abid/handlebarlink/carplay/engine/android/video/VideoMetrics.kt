package com.abid.handlebarlink.carplay.engine.android.video

import com.abid.handlebarlink.core.common.log.Logger
import kotlin.math.roundToLong

/** Why the decoder dropped a picture itself (the queue counts its own drops), and the keyframe request it makes. */
internal enum class DropReason(
    val keyFrameReason: String?,
) {
    /** Waiting for a keyframe after the chain broke. */
    WAIT_KEY("waiting for keyframe"),

    /** No configuration yet: ask the iPhone for a keyframe (and with it the configuration). */
    NO_CONFIG("no configuration"),

    /** No decoder (no hardware decoder, or waiting out a failure; it asks for a keyframe when it starts). */
    NO_DECODER(null),

    /** No surface to draw to: no keyframe until there is one. */
    NO_SURFACE(null),

    /** The picture's NAL lengths do not add up, or it does not fit (the feeder asks itself). */
    BAD(null),
}

/**
 * The screen's numbers, logged as one `carplay.video.stats` line per window so a device run can be compared with
 * CarLink's `DiPlay-VideoStats` line (DiPlay docs/SMOOTH_WIRELESS.md §4: rx, shown, decode p50/p90):
 * - `received`, `decoded`, `shown` (released to the surface), `rendered` (`onFrameRendered`), `fps` = shown per second;
 * - drops by reason: `dropped_full`, `dropped_superseded` (queue), `dropped_wait_key`, `dropped_no_config`,
 *   `dropped_no_decoder`,
 *   `dropped_no_surface`, `dropped_bad`;
 * - `decode_p50_ms`/`decode_p90_ms`: submit to decoder output; `render_p50_ms`/`render_p90_ms`: submit to the
 *   display (`onFrameRendered`'s time, docs: "the system time when the frame was rendered");
 * - `queue_max`, `recoveries`, `keyframe_requests`.
 * Each logged window is also handed to [report] ([CarPlayVideoStats], the CarPlay tab's link quality).
 * Confined to the decoder thread.
 */
internal class VideoMetrics(
    private val log: Logger,
    private val intervalNanos: Long,
    private val report: (CarPlayVideoStats) -> Unit = {},
) {
    private var windowStart = NOT_STARTED
    private var decoded = 0
    private var shown = 0
    private var rendered = 0
    private val drops = IntArray(DropReason.entries.size)
    private var recoveries = 0
    private var keyFrameRequests = 0
    private val decodeLatency = Samples()
    private val renderLatency = Samples()

    fun onDecoded(latencyNanos: Long) {
        decoded++
        decodeLatency.add(latencyNanos)
    }

    fun onShown() {
        shown++
    }

    fun onRendered(latencyNanos: Long) {
        rendered++
        renderLatency.add(latencyNanos)
    }

    fun onDropped(reason: DropReason) {
        drops[reason.ordinal]++
    }

    fun onRecovery() {
        recoveries++
    }

    fun onKeyFrameRequest() {
        keyFrameRequests++
    }

    /** Whether a window has ended at [now]; the first call starts the first window. */
    fun due(now: Long): Boolean {
        if (windowStart == NOT_STARTED) windowStart = now
        return now - windowStart >= intervalNanos
    }

    /** Logs the window that ended at [now] (unless nothing happened in it) and starts the next one. */
    fun log(
        now: Long,
        queue: QueueCounts,
        decoder: String?,
    ) {
        val seconds = (now - windowStart).toDouble() / NANOS_PER_SECOND
        val anything = queue.received > 0 || decoded > 0 || drops.any { it > 0 } || recoveries > 0
        if (anything && seconds > 0) {
            log.info(
                "carplay.video.stats",
                "decoder" to decoder,
                "received" to queue.received,
                "decoded" to decoded,
                "shown" to shown,
                "rendered" to rendered,
                "fps" to tenths(shown / seconds),
                "dropped_full" to queue.droppedFull,
                "dropped_superseded" to queue.droppedSuperseded,
                "dropped_wait_key" to drops[DropReason.WAIT_KEY.ordinal],
                "dropped_no_config" to drops[DropReason.NO_CONFIG.ordinal],
                "dropped_no_decoder" to drops[DropReason.NO_DECODER.ordinal],
                "dropped_no_surface" to drops[DropReason.NO_SURFACE.ordinal],
                "dropped_bad" to drops[DropReason.BAD.ordinal],
                "decode_p50_ms" to decodeLatency.percentileMillis(P50),
                "decode_p90_ms" to decodeLatency.percentileMillis(P90),
                "render_p50_ms" to renderLatency.percentileMillis(P50),
                "render_p90_ms" to renderLatency.percentileMillis(P90),
                "queue_max" to queue.highWater,
                "recoveries" to recoveries,
                "keyframe_requests" to keyFrameRequests,
            )
            report(
                CarPlayVideoStats(
                    received = queue.received,
                    shown = shown,
                    framesPerSecond = tenths(shown / seconds),
                    dropped = queue.droppedFull + queue.droppedSuperseded + drops[DropReason.BAD.ordinal],
                    decodeP90Millis = decodeLatency.percentileMillis(P90),
                    recoveries = recoveries,
                ),
            )
        }
        windowStart = now
        decoded = 0
        shown = 0
        rendered = 0
        drops.fill(0)
        recoveries = 0
        keyFrameRequests = 0
        decodeLatency.clear()
        renderLatency.clear()
    }

    /** Latency samples of one window; past [CAPACITY] samples the oldest are overwritten. */
    private class Samples {
        private val values = LongArray(CAPACITY)
        private var count = 0
        private var next = 0

        fun add(nanos: Long) {
            values[next] = nanos.coerceAtLeast(0)
            next = (next + 1) % CAPACITY
            if (count < CAPACITY) count++
        }

        fun clear() {
            count = 0
            next = 0
        }

        /** The [percent] percentile in milliseconds (one decimal), or null without samples. */
        fun percentileMillis(percent: Int): Double? {
            if (count == 0) return null
            val sorted = values.copyOf(count).also { it.sort() }
            return tenths(sorted[(count - 1) * percent / PERCENT].toDouble() / NANOS_PER_MILLI)
        }
    }

    private companion object {
        const val NOT_STARTED = Long.MIN_VALUE
        const val CAPACITY = 1024
        const val P50 = 50
        const val P90 = 90
        const val PERCENT = 100
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLI = 1_000_000.0
        const val TENTHS = 10.0

        fun tenths(value: Double): Double = (value * TENTHS).roundToLong() / TENTHS
    }
}

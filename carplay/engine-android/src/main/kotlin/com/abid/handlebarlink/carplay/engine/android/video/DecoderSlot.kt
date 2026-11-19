package com.abid.handlebarlink.carplay.engine.android.video

import com.abid.handlebarlink.core.common.log.Level
import com.abid.handlebarlink.core.common.log.Logger

/**
 * The one running decoder and how the next one is chosen: candidates from [chooseDecoders], each tried tuned and then
 * plain (DiPlay `videoDecoderAttempts`: some decoders reject the tuned parameters, "so a minimal format ... follow").
 * A decoder that fails [FAILURES_PER_DECODER] times before its first output gives way to the next candidate; when all
 * have failed, or [FAST_FAILURES] failures come within [FAST_FAILURE_WINDOW_NANOS], starting waits [RETRY_NANOS] so a
 * broken decoder is never recreated in a hot loop. Confined to the decoder thread.
 */
internal class DecoderSlot<S : Any>(
    private val platform: DecoderPlatform<S>,
    private val events: CodecEvents<S>,
    private val clock: MonotonicClock,
    private val log: Logger,
) {
    /** A started decoder. */
    class Active<S : Any>(
        val codec: VideoCodec<S>,
        val info: DecoderInfo,
        val startedNanos: Long,
    ) {
        /** Decoding without a surface (`detachOutputSurface`). */
        var detached = false
        var firstOutput = false
        var firstRendered = false
    }

    /** The running decoder, if any. */
    var active: Active<S>? = null
        private set

    /** Input buffers the running decoder has handed out and not yet been given back. */
    val freeInputs = ArrayDeque<Int>()

    private val failureTimes = LongArray(FAST_FAILURES) { NEVER }
    private val vendorParametersLogged = HashSet<String>()
    private var candidates: List<DecoderInfo>? = null
    private var candidateIndex = 0
    private var failuresOnCandidate = 0
    private var failureCount = 0
    private var retryNotBefore = NEVER
    private var maxInputSize = 0

    /** The display changed: choose again, and size the input for it. */
    fun forgetCandidates() {
        candidates = null
        candidateIndex = 0
        maxInputSize = 0
    }

    /** Starts a decoder unless one runs or a failure is being waited out; true when one runs afterwards. */
    fun start(
        config: AvcConfig,
        surface: S,
        display: Display,
    ): Boolean {
        val now = clock.nanos()
        if (active == null && now >= retryNotBefore) active = firstThatStarts(config, surface, display, now)
        return active != null
    }

    /** Releases the running decoder, if any. */
    fun release() {
        val running = active ?: return
        active = null
        freeInputs.clear()
        running.codec.release()
    }

    /** The running decoder failed: release it and decide when and with which candidate to try again. */
    fun onFailure(
        hadOutput: Boolean,
        transient: Boolean,
    ) {
        val now = clock.nanos()
        release()
        if (!hadOutput && ++failuresOnCandidate >= FAILURES_PER_DECODER) {
            candidateIndex++
            failuresOnCandidate = 0
        }
        failureTimes[failureCount % FAST_FAILURES] = now
        failureCount++
        val oldest = failureTimes[failureCount % FAST_FAILURES] // the first of the last FAST_FAILURES failures
        val failingFast = oldest != NEVER && now - oldest < FAST_FAILURE_WINDOW_NANOS
        if (transient || failingFast) retryNotBefore = now + RETRY_NANOS
    }

    /** The running decoder produced its first output: it works. */
    fun onFirstOutput() {
        failuresOnCandidate = 0
    }

    /** A picture did not fit: the next decoder asks for [MAX_INPUT_SIZE]. False when it already did. */
    fun useLargestInput(): Boolean = (maxInputSize < MAX_INPUT_SIZE).also { if (it) maxInputSize = MAX_INPUT_SIZE }

    private fun firstThatStarts(
        config: AvcConfig,
        surface: S,
        display: Display,
        now: Long,
    ): Active<S>? {
        val list = candidateList(display)
        val inputSize = if (maxInputSize > 0) maxInputSize else inputSizeFor(display.width, display.height)
        val setup = StreamSetup(display, config, inputSize)
        var started: Active<S>? = null
        while (started == null && candidateIndex < list.size) {
            val info = list[candidateIndex]
            started = Tuning.entries.firstNotNullOfOrNull { tryStart(info, it, setup, surface) }
            if (started == null) {
                candidateIndex++
                failuresOnCandidate = 0
            }
        }
        if (started == null) {
            candidateIndex = 0
            retryNotBefore = now + RETRY_NANOS
            log.error(
                "carplay.video.decoder_unavailable",
                null,
                "candidates" to list.size,
                "retry_ms" to RETRY_NANOS / NANOS_PER_MILLI,
            )
        } else {
            freeInputs.clear()
        }
        return started
    }

    private fun candidateList(display: Display): List<DecoderInfo> =
        candidates ?: chooseDecoders(platform.catalog.decoders(AVC_MIME, display.width, display.height)).also {
            candidates = it
            log.info("carplay.video.candidates", "decoders" to it.joinToString(",") { info -> info.name })
        }

    private fun tryStart(
        info: DecoderInfo,
        tuning: Tuning,
        setup: StreamSetup,
        surface: S,
    ): Active<S>? {
        var codec: VideoCodec<S>? = null
        try {
            val created = platform.create(info.name, events)
            codec = created
            val vendor = created.vendorParameters()
            if (vendorParametersLogged.add(info.name)) {
                log.info("carplay.video.vendor_parameters", "decoder" to info.name, "names" to vendor.joinToString(","))
            }
            val format = decoderFormat(tuning, info, vendor, setup)
            created.configure(format, surface)
            created.start()
            log.info(
                "carplay.video.decoder",
                "name" to info.name,
                "tuning" to tuning.name.lowercase(),
                "low_latency_feature" to info.lowLatency,
                "low_latency_key" to format.lowLatency,
                "vendor_keys" to format.vendorKeys.joinToString(","),
                "operating_rate" to format.operatingRate,
                "size" to "${format.width}x${format.height}",
                "max_input" to format.maxInputSize,
                "detached_surface" to info.detachedSurface,
            )
            return Active(created, info, clock.nanos())
        } catch (e: CodecCallException) {
            codec?.release()
            log.log(
                Level.WARN,
                "carplay.video.configure_failed",
                e,
                "name" to info.name,
                "tuning" to tuning.name.lowercase(),
            )
        }
        return null
    }

    internal companion object {
        const val NEVER = Long.MIN_VALUE
        const val NANOS_PER_MILLI = 1_000_000L
        const val RETRY_NANOS = 1_000_000_000L
        const val FAILURES_PER_DECODER = 2
        const val FAST_FAILURES = 4
        const val FAST_FAILURE_WINDOW_NANOS = 10_000_000_000L
    }
}

/** Asks the iPhone for a keyframe at most once a second (DiPlay `requestKeyFrameIfDue`). */
internal class KeyFrameRequests(
    private val listener: ScreenDecoder.Listener,
    private val clock: MonotonicClock,
    private val log: Logger,
    private val metrics: VideoMetrics,
) {
    private var last = DecoderSlot.NEVER

    fun request(reason: String) {
        val now = clock.nanos()
        if (last != DecoderSlot.NEVER && now - last < INTERVAL_NANOS) return
        last = now
        metrics.onKeyFrameRequest()
        log.info("carplay.video.keyframe_request", "reason" to reason)
        listener.onKeyFrameNeeded()
    }

    private companion object {
        const val INTERVAL_NANOS = 1_000_000_000L
    }
}

/**
 * Our own strictly increasing presentation times (microseconds on the clock's base) for queued pictures, and the
 * submit time behind each, so an output or a rendered frame maps back to when its picture arrived.
 */
internal class PresentationTimes(
    private val clock: MonotonicClock,
) {
    private val presentation = LongArray(CAPACITY) { DecoderSlot.NEVER }
    private val submitted = LongArray(CAPACITY)
    private var next = 0
    private var last = 0L

    /** The presentation time for a picture submitted at [submittedNanos]. */
    fun stamp(submittedNanos: Long): Long {
        last = maxOf(clock.nanos() / NANOS_PER_MICRO, last + 1)
        presentation[next] = last
        submitted[next] = submittedNanos
        next = (next + 1) % CAPACITY
        return last
    }

    /** When the picture with [presentationMicros] was submitted, or [DecoderSlot.NEVER] when it is not known. */
    fun submittedAt(presentationMicros: Long): Long {
        val slot = presentation.indexOf(presentationMicros)
        return if (slot >= 0) submitted[slot] else DecoderSlot.NEVER
    }

    private companion object {
        const val CAPACITY = 64
        const val NANOS_PER_MICRO = 1_000L
    }
}

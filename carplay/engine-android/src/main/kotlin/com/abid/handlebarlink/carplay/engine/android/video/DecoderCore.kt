package com.abid.handlebarlink.carplay.engine.android.video

import com.abid.handlebarlink.core.common.log.Logger

/**
 * The screen decoder's logic, confined to the decoder thread: what the decoder is fed, what it shows and how it
 * recovers. [AndroidScreenDecoder] runs it on a `HandlerThread` with `MediaCodec`; tests run it with fakes.
 *
 * - **Prewarm.** The decoder is created, configured and started as soon as the display is known and there is a
 *   surface (or the decoder supports a detached surface), without CSD; the first configuration then reaches it as a
 *   codec-config buffer. A cold start cost about 200 ms on the Poco (list 100 ms, create and configure 94 ms), which
 *   otherwise falls on the first pictures. Until its first output the queue keeps every picture
 *   ([FrameQueue.starting]).
 * - **Configuration before pictures.** No picture is fed before the first configuration; pictures before it are
 *   dropped. Each decoder starts with the configuration's SPS/PPS as CSD and is fed from a keyframe on. A changed
 *   configuration replaces the decoder (DiPlay `configureDecoder`), an unchanged one keeps it. Units are taken in
 *   order, so pictures queued before a new configuration still go to the old decoder.
 * - **Keyframes.** Whenever the reference chain is broken (a new decoder, an error, dropped pictures) pictures are
 *   dropped until the next IDR, and the iPhone is asked for one ([KeyFrameRequests]; DiPlay `VideoReferenceChain`).
 * - **Render at once.** Each output is released with the current time as its render time
 *   (`releaseOutputBuffer(index, now)`): a `SurfaceView` shows it at the next VSYNC, and of several on one VSYNC only
 *   the last (MediaCodec reference, `releaseOutputBuffer(int, long)`). A render time far from now would put the
 *   surface in a mode that "will not drop frames", where a backlog only adds latency. Pacing on the iPhone's frame
 *   times (DiPlay's experimental "Smooth video") costs a display delay of about 100 ms, so it is not used.
 * - **Errors.** A codec error or a failed call releases the decoder and starts a new one from the configuration
 *   (DiPlay `recover`: "Recreate with codec-specific data: flush can discard CSD before the first output");
 *   [DecoderSlot] decides which and when. Callbacks of a released decoder are ignored.
 * - **Surface.** Without a surface the decoder is released (pictures are dropped) unless it supports a detached
 *   surface (API 35), where it keeps decoding without drawing. A new surface is switched in with `setOutputSurface`
 *   (or a new decoder) and asks for a keyframe, so a still screen shows at once (DiPlay `RefreshPicture`).
 */
internal class DecoderCore<S : Any>(
    private val platform: DecoderPlatform<S>,
    private val queue: FrameQueue,
    private val listener: ScreenDecoder.Listener,
    private val clock: MonotonicClock,
    private val log: Logger,
    statsIntervalNanos: Long,
) : CodecEvents<S> {
    private val metrics = VideoMetrics(log, statsIntervalNanos)
    private val slot = DecoderSlot(platform, this, clock, log, metrics)
    private val keyFrames = KeyFrameRequests(listener, clock, log, metrics)
    private val times = PresentationTimes(clock)

    private var display: Display? = null
    private var surface: S? = null
    private val configs = ConfigHistory(log)
    private val config: AvcConfig? get() = configs.current
    private var needsKeyFrame = true
    private var released = false

    /** The running decoder's name, for tests and the log. */
    val decoderName: String? get() = slot.active?.codec?.name

    fun configure(next: Display) {
        val valid = next.width > 0 && next.height > 0 && next.framesPerSecond > 0
        if (!valid) log.warn("carplay.video.display_invalid", "width" to next.width, "height" to next.height)
        if (released || !valid || next == display) return
        display = next
        slot.forgetCandidates()
        log.info("carplay.video.display", "width" to next.width, "height" to next.height, "fps" to next.framesPerSecond)
        surface?.let { platform.setFrameRate(it, next.framesPerSecond) }
        // A running decoder is replaced for the new size; otherwise one is prewarmed now, before the stream starts.
        startFresh(if (slot.active != null) "display changed" else null, replace = slot.active != null)
    }

    fun attach(next: S) {
        if (released || next === surface) return
        surface = next
        display?.let { platform.setFrameRate(next, it.framesPerSecond) }
        val running = slot.active
        val kept =
            running != null &&
                try {
                    running.codec.setOutputSurface(next)
                    running.detached = false
                    true
                } catch (e: CodecCallException) {
                    log.warn(
                        "carplay.video.surface_switch_failed",
                        "decoder" to running.codec.name,
                        "detail" to e.failure.detail,
                    )
                    false
                }
        log.info("carplay.video.surface", "state" to "attached", "decoder" to if (kept) "kept" else "new")
        if (!kept) {
            startFresh("surface attached", replace = true)
        } else if (config !=
            null
        ) {
            keyFrames.request("surface attached")
        }
        pump()
    }

    fun detach() {
        if (surface == null) return
        surface = null
        val running = slot.active
        val kept =
            running != null &&
                running.info.detachedSurface &&
                try {
                    running.codec.detachOutputSurface()
                    running.detached = true
                    true
                } catch (e: CodecCallException) {
                    log.warn(
                        "carplay.video.detach_failed",
                        "decoder" to running.codec.name,
                        "detail" to e.failure.detail,
                    )
                    false
                }
        if (!kept) {
            slot.release()
            needsKeyFrame = true
            repeat(queue.dropPictures()) { metrics.onDropped(DropReason.NO_SURFACE) }
        }
        val decoder =
            if (kept) {
                "kept"
            } else if (running != null) {
                "released"
            } else {
                "none"
            }
        log.info("carplay.video.surface", "state" to "detached", "decoder" to decoder)
    }

    /** Feeds what the queue holds into the decoder's free input buffers. */
    fun pump() {
        if (released) return
        if (queue.takeChainBroken()) {
            needsKeyFrame = true
            keyFrames.request("queue full")
        }
        var going = true
        while (going) {
            going =
                when (val unit = queue.peek()) {
                    null -> {
                        false
                    }

                    is PendingUnit.Config -> {
                        handleConfig(unit)
                    }

                    is PendingUnit.Picture -> {
                        handlePicture(unit)
                    }
                }
        }
    }

    /** About once a second: restarts a decoder that is waiting out a failure, and logs the stats when due. */
    fun tick() {
        if (released) return
        if (slot.active == null && startFresh("decoder restarted")) pump()
        val now = clock.nanos()
        if (metrics.due(now)) metrics.log(now, queue.takeCounts(), decoderName)
    }

    fun release() {
        if (released) return
        released = true
        slot.release()
        while (true) {
            val unit = queue.poll() ?: break
            if (unit is PendingUnit.Picture) queue.recycle(unit)
        }
        log.info("carplay.video.released")
    }

    override fun onInputAvailable(
        codec: VideoCodec<S>,
        index: Int,
    ) {
        if (codec !== slot.active?.codec) return
        slot.freeInputs.addLast(index)
        pump()
    }

    override fun onOutputAvailable(
        codec: VideoCodec<S>,
        index: Int,
        presentationMicros: Long,
    ) {
        val running = slot.active?.takeIf { it.codec === codec } ?: return
        val now = clock.nanos()
        val submitted = times.submittedAt(presentationMicros)
        if (submitted != DecoderSlot.NEVER) metrics.onDecoded(now - submitted)
        val show = surface != null && !running.detached
        try {
            codec.releaseOutput(index, if (show) now else null)
        } catch (e: CodecCallException) {
            if (slot.fail(running, e.failure)) startFresh("decoder recovered", replace = true)
            return
        }
        if (show) metrics.onShown()
        if (!running.firstOutput) {
            running.firstOutput = true
            queue.starting = false
            slot.onFirstOutput()
            val afterMs = (now - running.startedNanos) / DecoderSlot.NANOS_PER_MILLI
            log.info("carplay.video.first_output", "decoder" to codec.name, "after_ms" to afterMs)
        }
    }

    override fun onOutputFormatChanged(
        codec: VideoCodec<S>,
        description: String,
    ) {
        if (codec ===
            slot.active?.codec
        ) {
            log.info("carplay.video.output_format", "decoder" to codec.name, "format" to description)
        }
    }

    override fun onError(
        codec: VideoCodec<S>,
        failure: CodecFailure,
    ) {
        val running = slot.active?.takeIf { it.codec === codec } ?: return
        if (slot.fail(running, failure)) startFresh("decoder recovered", replace = true)
    }

    override fun onRendered(
        codec: VideoCodec<S>,
        presentationMicros: Long,
        renderedNanos: Long,
    ) {
        val running = slot.active?.takeIf { it.codec === codec } ?: return
        val submitted = times.submittedAt(presentationMicros)
        if (submitted != DecoderSlot.NEVER) metrics.onRendered(renderedNanos - submitted)
        if (!running.firstRendered) {
            running.firstRendered = true
            log.info("carplay.video.first_frame", "decoder" to codec.name)
            listener.onFirstFrameRendered()
        }
    }

    /** Drops, feeds or holds the picture at the head of the queue; false when it must wait for an input buffer. */
    private fun handlePicture(unit: PendingUnit.Picture): Boolean {
        val running =
            slot.active ?: run {
                startFresh(reason = null)
                slot.active
            }
        val dropReason =
            when {
                surface == null && (running == null || config == null) -> {
                    DropReason.NO_SURFACE
                }

                config == null -> {
                    DropReason.NO_CONFIG
                }

                running == null -> {
                    DropReason.NO_DECODER
                }

                needsKeyFrame && !unit.idr -> {
                    DropReason.WAIT_KEY
                }

                else -> {
                    null
                }
            }
        if (dropReason == null && slot.freeInputs.isEmpty()) return false
        queue.poll()
        if (dropReason == null) {
            feed(checkNotNull(running), unit)
        } else {
            metrics.onDropped(dropReason)
            queue.recycle(unit)
            dropReason.keyFrameReason?.let(keyFrames::request)
        }
        return true
    }

    private fun feed(
        running: DecoderSlot.Active<S>,
        unit: PendingUnit.Picture,
    ) {
        val index = slot.freeInputs.removeFirst()
        val framing = config?.nalLengthSize ?: AvcConfig.ANNEX_B
        try {
            val buffer = running.codec.inputBuffer(index) ?: throw CodecCallException(MISSING_INPUT)
            when (val written = Avc.writeAnnexB(unit.bytes, unit.offset, unit.length, framing, buffer)) {
                Avc.MALFORMED -> {
                    slot.freeInputs.addFirst(index)
                    metrics.onDropped(DropReason.BAD)
                    needsKeyFrame = true
                    keyFrames.request("malformed picture")
                }

                Avc.TOO_LARGE -> {
                    slot.freeInputs.addFirst(index)
                    metrics.onDropped(DropReason.BAD)
                    needsKeyFrame = true
                    if (slot.useLargestInput()) {
                        log.warn(
                            "carplay.video.input_too_small",
                            "picture_bytes" to unit.length,
                            "next_max_input" to MAX_INPUT_SIZE,
                        )
                        startFresh("picture larger than the input buffer", replace = true)
                    } else {
                        keyFrames.request("picture larger than the largest input")
                    }
                }

                else -> {
                    val kind = if (unit.idr) InputKind.KEY_FRAME else InputKind.PICTURE
                    running.codec.queueInput(index, written, times.stamp(unit.submittedNanos), kind)
                    needsKeyFrame = false
                }
            }
        } catch (e: CodecCallException) {
            if (slot.fail(running, e.failure)) startFresh("decoder recovered", replace = true)
        } finally {
            queue.recycle(unit)
        }
    }

    /**
     * Takes the configuration at the head of the queue. A prewarmed decoder without CSD gets it as a codec-config
     * buffer (it waits for a free input); any other change replaces the decoder. False when it must wait.
     */
    private fun handleConfig(unit: PendingUnit.Config): Boolean {
        val running = slot.active
        val prewarmed = running != null && running.csd == null && configs.differs(unit.config)
        if (prewarmed && slot.freeInputs.isEmpty()) return false
        queue.poll()
        val next = unit.config
        if (configs.accept(next) && next != null) {
            val queued =
                running != null &&
                    prewarmed &&
                    try {
                        slot.queueCsd(running, next)
                    } catch (e: CodecCallException) {
                        log.warn(
                            "carplay.video.csd_failed",
                            "decoder" to running.codec.name,
                            "detail" to e.failure.detail,
                        )
                        false
                    }
            if (queued) needsKeyFrame = true else startFresh(reason = null, replace = true)
        }
        return true
    }

    /**
     * Starts a decoder (after releasing the running one when [replace]) once the display or the configuration is
     * known; without a configuration it is prewarmed, without a surface only a decoder that supports a detached one
     * starts. Asks for a keyframe with [reason] when one started for a running stream. True when one started.
     */
    private fun startFresh(
        reason: String?,
        replace: Boolean = false,
    ): Boolean {
        if (replace) {
            slot.release()
            needsKeyFrame = true
        }
        val idle = !released && slot.active == null
        val size = display ?: config?.let { DEFAULT_DISPLAY }
        val started = idle && size != null && slot.start(config, surface, size)
        if (started) {
            needsKeyFrame = true
            queue.starting = true
            if (reason != null && config != null) keyFrames.request(reason)
        }
        return started
    }

    private companion object {
        /** Used when [configure] was never called; decoders read the real size from the SPS. */
        val DEFAULT_DISPLAY = Display(width = 1920, height = 1080, framesPerSecond = 60)

        val MISSING_INPUT = CodecFailure(recoverable = false, transient = false, detail = "input buffer missing")
    }
}

/** The stream's current configuration: a changed one replaces the decoder, an unchanged one keeps it. */
internal class ConfigHistory(
    private val log: Logger,
) {
    var current: AvcConfig? = null
        private set

    /** Whether [next] is valid and differs from the current configuration. */
    fun differs(next: AvcConfig?): Boolean {
        val previous = current
        return next != null && (previous == null || !previous.sameAs(next))
    }

    /** Takes [next] when it is valid and differs from the current one; true when it did. */
    fun accept(next: AvcConfig?): Boolean {
        val previous = current
        if (next == null) log.warn("carplay.video.config_invalid")
        val changed = differs(next)
        if (next != null && changed) {
            current = next
            log.info(
                "carplay.video.config",
                "sps_bytes" to next.csd0.size,
                "pps_bytes" to next.csd1.size,
                "framing" to if (next.nalLengthSize == AvcConfig.ANNEX_B) "annexb" else "length${next.nalLengthSize}",
                "replaces" to (previous != null),
            )
        }
        return changed
    }
}

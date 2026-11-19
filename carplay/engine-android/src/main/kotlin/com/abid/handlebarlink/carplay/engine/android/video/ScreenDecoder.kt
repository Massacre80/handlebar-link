package com.abid.handlebarlink.carplay.engine.android.video

import android.view.Surface
import com.abid.handlebarlink.core.common.log.Logger

/**
 * Decodes CarPlay's main screen (H.264 from the iPhone) with the hardware decoder straight into a `SurfaceView`'s
 * surface, at the lowest latency the decoder allows (docs/PLAN.md "Performance rules"). It runs on its own thread
 * and never blocks the caller, except [detach], which must not return while the decoder still draws to the surface.
 *
 * Feed it what the screen stream carries, in order: the codec configuration (`avcC`, or Annex B SPS/PPS) with
 * `isConfig = true`, then pictures framed as that configuration says (length-prefixed after an `avcC`, Annex B after
 * an Annex B configuration). A changed configuration (a new resolution) replaces the decoder; an unchanged one is
 * ignored. When the decoder needs a keyframe (after it started, after an error, after pictures were dropped, after the
 * surface came back) it calls [Listener.onKeyFrameNeeded] at most once a second.
 *
 * Every call is thread-safe; [submit] is meant for one thread (the screen stream's).
 */
public interface ScreenDecoder {
    /** The picture size and frame rate the iPhone was asked for (the session's display); call before the stream. */
    public fun configure(
        width: Int,
        height: Int,
        framesPerSecond: Int,
    )

    /** Draws to [surface] from now on (`SurfaceHolder.Callback.surfaceCreated`). */
    public fun attach(surface: Surface)

    /**
     * Stops drawing to the current surface before it is destroyed (`surfaceDestroyed`): returns once the decoder has
     * let go of it, or after a short limit. Pictures that arrive without a surface are dropped.
     */
    public fun detach()

    /**
     * Hands over one unit of the screen stream. The decoder owns [frame] until it gives it back through
     * [FrameRecycler.recycle] (the caller can then reuse it); a configuration is given back before this returns.
     * Returns false when a picture was dropped at once (the decoder is far behind, or released); a dropped frame is
     * recycled too, so every submitted frame comes back exactly once.
     *
     * @param ptsMicros the iPhone's frame time, for the log; pictures are shown as soon as they are decoded.
     */
    public fun submit(
        frame: ByteArray,
        offset: Int,
        length: Int,
        ptsMicros: Long,
        isConfig: Boolean,
    ): Boolean

    /** Releases the decoder and its thread; every later call does nothing. */
    public fun release()

    /** Takes back a frame's bytes once the decoder no longer needs them (on the submitting or the decoder thread). */
    public fun interface FrameRecycler {
        /** [frame] may be reused. */
        public fun recycle(frame: ByteArray)
    }

    /** What the decoder needs from the session. Called on the decoder thread; must not block. */
    public interface Listener {
        /** Ask the iPhone for a keyframe (AirPlay `forceKeyFrame`). */
        public fun onKeyFrameNeeded()

        /** The first picture of this decoder reached the screen. */
        public fun onFirstFrameRendered() {}
    }

    /** Limits and timing. */
    public data class Options(
        /** Pictures that may wait for the decoder, a memory guard (DiPlay `VideoDecodeQueue`: 60). */
        val maxQueuedPictures: Int = DEFAULT_MAX_QUEUED_PICTURES,
        /** Bytes that may wait for the decoder (DiPlay `VideoDecodeQueue`: 8 MiB). */
        val maxQueuedBytes: Long = MAX_INPUT_SIZE.toLong(),
        /**
         * How long the oldest picture may wait for a running decoder before the waiting ones are dropped (DiPlay
         * `VideoBacklogRecovery`: 250 ms).
         */
        val maxQueueAgeMillis: Long = DEFAULT_MAX_QUEUE_AGE_MILLIS,
        /** The same while a decoder starts and drains what waited meanwhile (DiPlay: 1.5 s hard limit). */
        val maxStartupQueueAgeMillis: Long = DEFAULT_MAX_STARTUP_QUEUE_AGE_MILLIS,
        /** How often the `carplay.video.stats` line is logged. */
        val statsIntervalMillis: Long = DEFAULT_STATS_INTERVAL_MILLIS,
        /** How long [detach] waits for the decoder to let go of the surface. */
        val detachTimeoutMillis: Long = DEFAULT_DETACH_TIMEOUT_MILLIS,
    )

    private companion object {
        const val DEFAULT_MAX_QUEUED_PICTURES = 60
        const val DEFAULT_MAX_QUEUE_AGE_MILLIS = 250L
        const val DEFAULT_MAX_STARTUP_QUEUE_AGE_MILLIS = 1_500L
        const val DEFAULT_STATS_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_DETACH_TIMEOUT_MILLIS = 500L
    }
}

/**
 * Creates the screen decoder. Only constructs objects (main-thread safe, docs/LESSONS.md 60); its thread starts on
 * first use. Logs under `carplay.video.*` through [logger].
 */
public fun createScreenDecoder(
    logger: Logger,
    listener: ScreenDecoder.Listener,
    recycler: ScreenDecoder.FrameRecycler = ScreenDecoder.FrameRecycler {},
    options: ScreenDecoder.Options = ScreenDecoder.Options(),
): ScreenDecoder = AndroidScreenDecoder(logger, listener, recycler, options)

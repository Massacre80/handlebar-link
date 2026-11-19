package com.abid.handlebarlink.carplay.engine.android.video

import java.nio.ByteBuffer

/**
 * The part of `MediaCodec` the decoder uses, over an output surface of type [S] (`android.view.Surface` on the
 * phone, a token in tests). Every call runs on the decoder thread. A failing call throws [CodecCallException], whatever
 * `MediaCodec` threw (`IllegalStateException`, of which `CodecException` is one, or `IllegalArgumentException`).
 */
internal interface VideoCodec<S : Any> {
    val name: String

    /** `getSupportedVendorParameters()` (API 31); empty when the codec lists none. */
    fun vendorParameters(): List<String>

    fun configure(
        format: DecoderFormat,
        surface: S,
    )

    fun start()

    /** The input buffer [index] handed out by [CodecEvents.onInputAvailable], cleared; null when it is gone. */
    fun inputBuffer(index: Int): ByteBuffer?

    fun queueInput(
        index: Int,
        size: Int,
        presentationMicros: Long,
        keyFrame: Boolean,
    )

    /** `releaseOutputBuffer(index, renderAtNanos)` when set, else `releaseOutputBuffer(index, false)`. */
    fun releaseOutput(
        index: Int,
        renderAtNanos: Long?,
    )

    /** `setOutputSurface` (API 23). */
    fun setOutputSurface(surface: S)

    /** `detachOutputSurface` (API 35); only called when [DecoderInfo.detachedSurface] is set. */
    fun detachOutputSurface()

    /** `stop()` then `release()`, best effort: never throws. */
    fun release()
}

/** What `MediaCodec.CodecException` says about an error (or what another failed call said). */
internal data class CodecFailure(
    /** `isRecoverable`. */
    val recoverable: Boolean,
    /** `isTransient`: resources are busy for now. */
    val transient: Boolean,
    val detail: String,
)

/** A failed codec call, with what the platform said about it. */
internal class CodecCallException(
    val failure: CodecFailure,
    cause: Throwable? = null,
) : Exception(failure.detail, cause)

/** `MediaCodec.Callback` and `OnFrameRenderedListener`, on the decoder thread, with the codec they came from. */
internal interface CodecEvents<S : Any> {
    fun onInputAvailable(
        codec: VideoCodec<S>,
        index: Int,
    )

    fun onOutputAvailable(
        codec: VideoCodec<S>,
        index: Int,
        presentationMicros: Long,
    )

    fun onOutputFormatChanged(
        codec: VideoCodec<S>,
        description: String,
    )

    fun onError(
        codec: VideoCodec<S>,
        failure: CodecFailure,
    )

    /** `onFrameRendered`: the frame reached the display at [renderedNanos] (the `System.nanoTime` base). */
    fun onRendered(
        codec: VideoCodec<S>,
        presentationMicros: Long,
        renderedNanos: Long,
    )
}

/** Creates codecs and tells the display the frame rate; the Android one is [AndroidDecoderPlatform]. */
internal interface DecoderPlatform<S : Any> {
    val catalog: DecoderCatalog

    /** `MediaCodec.createByCodecName` with [events] registered before configure (async mode). */
    @Throws(CodecCallException::class)
    fun create(
        name: String,
        events: CodecEvents<S>,
    ): VideoCodec<S>

    /** `Surface.setFrameRate`, best effort. */
    fun setFrameRate(
        surface: S,
        framesPerSecond: Int,
    )
}

/**
 * Monotonic nanoseconds on the base `MediaCodec` render timestamps use (`System.nanoTime`, CLOCK_MONOTONIC). The
 * app's `TimeProvider` only has `elapsedRealtime` milliseconds, which is another base (it counts deep sleep).
 */
internal fun interface MonotonicClock {
    fun nanos(): Long
}

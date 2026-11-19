package com.abid.handlebarlink.carplay.engine.android.video

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import com.abid.handlebarlink.core.common.log.Level
import com.abid.handlebarlink.core.common.log.Logger
import java.io.IOException
import java.nio.ByteBuffer

/**
 * `MediaCodec` in asynchronous mode, its callbacks on the decoder thread's [handler] (MediaCodec reference,
 * "Asynchronous Processing using Buffers": `setCallback` before `configure`).
 */
internal class AndroidDecoderPlatform(
    private val handler: Handler,
    private val log: Logger,
) : DecoderPlatform<Surface> {
    override val catalog: DecoderCatalog = MediaCodecCatalog

    override fun create(
        name: String,
        events: CodecEvents<Surface>,
    ): VideoCodec<Surface> {
        val codec =
            try {
                MediaCodec.createByCodecName(name)
            } catch (e: IOException) {
                throw CodecCallException(CodecFailure(recoverable = false, transient = false, detail = e.toString()), e)
            } catch (e: IllegalArgumentException) {
                throw CodecCallException(CodecFailure(recoverable = false, transient = false, detail = e.toString()), e)
            }
        return MediaCodecVideoCodec(codec, events, handler)
    }

    override fun setFrameRate(
        surface: Surface,
        framesPerSecond: Int,
    ) {
        // Video content: FIXED_SOURCE, and only a seamless switch (Surface reference, setFrameRate(float, int, int)).
        try {
            surface.setFrameRate(
                framesPerSecond.toFloat(),
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
        } catch (e: IllegalArgumentException) {
            log.log(Level.WARN, "carplay.video.frame_rate_failed", e, "fps" to framesPerSecond)
        } catch (e: IllegalStateException) {
            log.log(Level.WARN, "carplay.video.frame_rate_failed", e, "fps" to framesPerSecond)
        }
    }
}

/** The platform's decoders through `MediaCodecList(REGULAR_CODECS)`, in its order. */
internal object MediaCodecCatalog : DecoderCatalog {
    override fun decoders(
        mime: String,
        width: Int,
        height: Int,
    ): List<DecoderInfo> =
        MediaCodecList(
            MediaCodecList.REGULAR_CODECS,
        ).codecInfos.filter { !it.isEncoder && mime in it.supportedTypes }.map {
            val capabilities = it.getCapabilitiesForType(mime)
            DecoderInfo(
                name = it.name,
                hardware = it.isHardwareAccelerated,
                softwareOnly = it.isSoftwareOnly,
                alias = it.isAlias,
                lowLatency = capabilities.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency),
                secureOnly = capabilities.isFeatureRequired(CodecCapabilities.FEATURE_SecurePlayback),
                sizeSupported = capabilities.videoCapabilities?.isSizeSupported(width, height) == true,
                detachedSurface = supportsDetachedSurface(capabilities),
            )
        }

    /** FEATURE_DetachedSurface exists from API 35 (the one branch for it); earlier the decoder is released instead. */
    private fun supportsDetachedSurface(capabilities: CodecCapabilities): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            capabilities.isFeatureSupported(CodecCapabilities.FEATURE_DetachedSurface)
}

/** One `MediaCodec`; every call runs on the decoder thread, and so do its callbacks. */
internal class MediaCodecVideoCodec(
    private val codec: MediaCodec,
    events: CodecEvents<Surface>,
    handler: Handler,
) : VideoCodec<Surface> {
    override val name: String = codec.name

    init {
        val self = this
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                ) = events.onInputAvailable(self, index)

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) = events.onOutputAvailable(self, index, info.presentationTimeUs)

                override fun onError(
                    codec: MediaCodec,
                    e: MediaCodec.CodecException,
                ) = events.onError(self, failureOf(e))

                override fun onOutputFormatChanged(
                    codec: MediaCodec,
                    format: MediaFormat,
                ) = events.onOutputFormatChanged(self, format.toString())
            },
            handler,
        )
        codec.setOnFrameRenderedListener(
            { _, presentationTimeUs, nanoTime -> events.onRendered(self, presentationTimeUs, nanoTime) },
            handler,
        )
    }

    override fun vendorParameters(): List<String> = call { codec.supportedVendorParameters }

    // The configure flags' @IntDef in android.jar (API 37) lists ENCODE, USE_BLOCK_MODEL and USE_CRYPTO_ASYNC but not
    // CONFIGURE_FLAG_DETACHED_SURFACE (API 35), which the MediaCodec reference documents for "a video decoder" with a
    // null surface; lint's WrongConstant is a gap in that annotation, so it is suppressed on this one call only.
    @SuppressLint("WrongConstant")
    override fun configure(
        format: DecoderFormat,
        surface: Surface?,
    ) = call {
        when {
            surface != null -> {
                codec.configure(format.toMediaFormat(), surface, null, 0)
            }

            // Only decoders with FEATURE_DetachedSurface (API 35) are started without a surface (DecoderSlot).
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                codec.configure(format.toMediaFormat(), null, null, MediaCodec.CONFIGURE_FLAG_DETACHED_SURFACE)
            }

            else -> {
                error("a detached surface needs API 35")
            }
        }
    }

    override fun start() = call { codec.start() }

    override fun inputBuffer(index: Int): ByteBuffer? = call { codec.getInputBuffer(index) }

    override fun queueInput(
        index: Int,
        size: Int,
        presentationMicros: Long,
        kind: InputKind,
    ) = call { codec.queueInputBuffer(index, 0, size, presentationMicros, flagsOf(kind)) }

    override fun releaseOutput(
        index: Int,
        renderAtNanos: Long?,
    ) = call {
        if (renderAtNanos !=
            null
        ) {
            codec.releaseOutputBuffer(index, renderAtNanos)
        } else {
            codec.releaseOutputBuffer(index, false)
        }
    }

    override fun setOutputSurface(surface: Surface) = call { codec.setOutputSurface(surface) }

    override fun detachOutputSurface() =
        call {
            // DecoderInfo.detachedSurface is only ever true from API 35 (MediaCodecCatalog).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                codec.detachOutputSurface()
            } else {
                error("detachOutputSurface needs API 35")
            }
        }

    override fun release() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
            // Best effort: a codec in the error state cannot stop; release still frees it.
        }
        codec.release()
    }

    /** Runs a `MediaCodec` call, turning what it throws into a [CodecCallException]. */
    private inline fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            throw CodecCallException(failureOf(e), e)
        } catch (e: IllegalArgumentException) {
            throw CodecCallException(CodecFailure(recoverable = false, transient = false, detail = e.toString()), e)
        }

    private companion object {
        fun flagsOf(kind: InputKind): Int =
            when (kind) {
                InputKind.PICTURE -> 0
                InputKind.KEY_FRAME -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                InputKind.CODEC_CONFIG -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            }

        fun failureOf(error: IllegalStateException): CodecFailure =
            if (error is MediaCodec.CodecException) {
                CodecFailure(error.isRecoverable, error.isTransient, "${error.errorCode} ${error.diagnosticInfo}")
            } else {
                CodecFailure(recoverable = false, transient = false, detail = error.toString())
            }
    }
}

/** [DecoderFormat] as the `MediaFormat` `configure` takes (MediaFormat reference for each key). */
internal fun DecoderFormat.toMediaFormat(): MediaFormat =
    MediaFormat.createVideoFormat(mime, width, height).apply {
        // Without CSD the codec gets the SPS and PPS as one codec-config buffer after start() (MediaCodec reference,
        // "Codec-specific Data": "In case of H.264 AVC, you can also concatenate all codec-specific data").
        if (csd0.isNotEmpty()) setByteBuffer(CSD_0, ByteBuffer.wrap(csd0))
        if (csd1.isNotEmpty()) setByteBuffer(CSD_1, ByteBuffer.wrap(csd1))
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
        if (lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        if (realtime) setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (operatingRate > 0) setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate)
        vendorKeys.forEach { setInteger(it, 1) }
    }

private const val CSD_0 = "csd-0"
private const val CSD_1 = "csd-1"

/**
 * CLOCK_MONOTONIC, the base of `System.nanoTime` that `releaseOutputBuffer(index, renderTimestampNs)` and
 * `onFrameRendered` use (AOSP: `SystemClock.uptimeNanos` and `uptimeMillis` read `SYSTEM_TIME_MONOTONIC`).
 * `uptimeNanos` exists from API 35; before it, `uptimeMillis` is the same clock in whole milliseconds, close enough
 * for a render time of "now" and for latency in milliseconds. The one place the decoder reads a clock.
 */
internal object AndroidMonotonicClock : MonotonicClock {
    private const val NANOS_PER_MILLI = 1_000_000L

    override fun nanos(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            SystemClock.uptimeNanos()
        } else {
            SystemClock.uptimeMillis() * NANOS_PER_MILLI
        }
}

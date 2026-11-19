package com.abid.handlebarlink.carplay.engine.android.video

/** One installed video decoder, as `MediaCodecList(REGULAR_CODECS)` describes it for the stream's type and size. */
internal data class DecoderInfo(
    val name: String,
    /** `MediaCodecInfo.isHardwareAccelerated` (API 29). */
    val hardware: Boolean,
    /** `MediaCodecInfo.isSoftwareOnly` (API 29). */
    val softwareOnly: Boolean,
    /** `MediaCodecInfo.isAlias` (API 29): another name for a decoder that is listed itself. */
    val alias: Boolean,
    /** `CodecCapabilities.FEATURE_LowLatency` (API 30) is supported. */
    val lowLatency: Boolean,
    /** `FEATURE_SecurePlayback` is required: the decoder only takes protected content. */
    val secureOnly: Boolean,
    /** `VideoCapabilities.isSizeSupported` for the picture the iPhone was asked for. */
    val sizeSupported: Boolean,
    /** `CodecCapabilities.FEATURE_DetachedSurface` (API 35): `detachOutputSurface` keeps the decoder alive. */
    val detachedSurface: Boolean,
)

/** The decoders the platform offers for a stream, in `MediaCodecList` order (the platform's preference). */
internal fun interface DecoderCatalog {
    fun decoders(
        mime: String,
        width: Int,
        height: Int,
    ): List<DecoderInfo>
}

/**
 * The decoders to try, best first: hardware only (docs/PLAN.md "Performance rules": no software decoding), never an
 * alias or a secure-only decoder, only those that take the picture size; among those, the ones that advertise
 * `FEATURE_LowLatency` first, then the platform's order. DiPlay picks its low-latency decoder the same way
 * (`AndroidMediaSink.kt` `lowLatencyDecoderName`: "the first hardware decoder (not an alias) that advertises the
 * low-latency feature"; on a Qualcomm Tang that is `c2.qti.avc.decoder.low_latency`, while the default
 * `c2.qti.avc.decoder` held about two frames, DiPlay docs/SMOOTH_WIRELESS.md §3).
 */
internal fun chooseDecoders(all: List<DecoderInfo>): List<DecoderInfo> =
    all
        .filter { it.hardware && !it.softwareOnly && !it.alias && !it.secureOnly && it.sizeSupported }
        .sortedByDescending { it.lowLatency } // stable: equals keep the platform's order

/** How hard a configure attempt tunes the decoder. A decoder that refuses the tuned format gets the plain one. */
internal enum class Tuning {
    /** Low latency, realtime priority, operating rate and the vendor's low-latency keys. */
    TUNED,

    /** Only what decoding needs: CSD and the input size (DiPlay `videoDecoderAttempts`: "a minimal format"). */
    PLAIN,
}

/** Everything `MediaFormat` gets for one configure attempt; plain data, so the choice is testable on the JVM. */
internal data class DecoderFormat(
    val mime: String,
    val width: Int,
    val height: Int,
    val csd0: ByteArray,
    val csd1: ByteArray,
    /** `KEY_MAX_INPUT_SIZE`. */
    val maxInputSize: Int,
    /** `KEY_LOW_LATENCY` = 1 (API 30). */
    val lowLatency: Boolean,
    /** `KEY_PRIORITY` = 0 (realtime), API 23. */
    val realtime: Boolean,
    /** `KEY_OPERATING_RATE` in frames per second, 0 for none. */
    val operatingRate: Int,
    /** Vendor parameters set to 1. */
    val vendorKeys: List<String>,
) {
    // Arrays compare by content in tests and logs.
    override fun equals(other: Any?): Boolean =
        other is DecoderFormat &&
            mime == other.mime &&
            width == other.width &&
            height == other.height &&
            csd0.contentEquals(other.csd0) &&
            csd1.contentEquals(other.csd1) &&
            maxInputSize == other.maxInputSize &&
            lowLatency == other.lowLatency &&
            realtime == other.realtime &&
            operatingRate == other.operatingRate &&
            vendorKeys == other.vendorKeys

    override fun hashCode(): Int = name().hashCode()

    private fun name(): String = "$mime ${width}x$height"
}

/** The screen stream's type: H.264 (the only one the receiver offers so far). */
internal const val AVC_MIME = "video/avc"

/**
 * Qualcomm's vendor parameters that make its decoders output each frame as soon as it is decoded, in decode order
 * (DiPlay `AndroidMediaSink.kt` `vendorLowLatencyKeys` and `PICTURE_ORDER_PARAMETER`). Decode order is display order
 * here: the iPhone's SPS signals `max_num_reorder_frames` 0 and its frames have no B slices (DiPlay
 * docs/SMOOTH_WIRELESS.md §3). Set only when the decoder lists them (`getSupportedVendorParameters`, API 31).
 */
internal val LOW_LATENCY_VENDOR_KEYS =
    listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")

/** What every attempt for one stream shares: the display, the configuration and the input size. */
internal class StreamSetup(
    val display: Display,
    val config: AvcConfig,
    val maxInputSize: Int,
)

/** The format for one attempt with [decoder]; [vendorParameters] are those the created codec lists. */
internal fun decoderFormat(
    tuning: Tuning,
    decoder: DecoderInfo,
    vendorParameters: List<String>,
    stream: StreamSetup,
): DecoderFormat {
    val tuned = tuning == Tuning.TUNED
    return DecoderFormat(
        mime = AVC_MIME,
        width = stream.display.width,
        height = stream.display.height,
        csd0 = stream.config.csd0,
        csd1 = stream.config.csd1,
        maxInputSize = stream.maxInputSize,
        lowLatency = tuned && decoder.lowLatency,
        realtime = tuned,
        operatingRate = if (tuned) stream.display.framesPerSecond else 0,
        vendorKeys = if (tuned) LOW_LATENCY_VENDOR_KEYS.filter { it in vendorParameters } else emptyList(),
    )
}

/** The picture and frame rate the iPhone was asked for. */
internal data class Display(
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
)

private const val MACROBLOCK = 16
private const val YUV420_NUMERATOR = 3
private const val YUV420_DENOMINATOR = 2
private const val MIN_COMPRESSION_RATIO = 2
private const val MIN_INPUT_SIZE = 256L * 1024
internal const val MAX_INPUT_SIZE = 8 * 1024 * 1024

/**
 * The input buffer to ask for (CarLink's `videoInputSize`, after ExoPlayer's rule): the picture in whole macroblocks,
 * 4:2:0, over a minimum compression ratio of 2, kept between 256 KiB and 8 MiB. A picture that does not fit makes the
 * next decoder ask for [MAX_INPUT_SIZE].
 */
internal fun inputSizeFor(
    width: Int,
    height: Int,
): Int {
    val w = (width + MACROBLOCK - 1) / MACROBLOCK * MACROBLOCK.toLong()
    val h = (height + MACROBLOCK - 1) / MACROBLOCK * MACROBLOCK.toLong()
    return (w * h * YUV420_NUMERATOR / YUV420_DENOMINATOR / MIN_COMPRESSION_RATIO)
        .coerceIn(MIN_INPUT_SIZE, MAX_INPUT_SIZE.toLong())
        .toInt()
}

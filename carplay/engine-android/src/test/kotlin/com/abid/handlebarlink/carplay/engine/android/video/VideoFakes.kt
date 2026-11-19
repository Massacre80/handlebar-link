package com.abid.handlebarlink.carplay.engine.android.video

import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.nio.ByteBuffer

internal fun failure(detail: String) = CodecFailure(recoverable = false, transient = false, detail = detail)

/** An output surface in tests. */
internal class FakeSurface(
    val label: String,
) {
    override fun toString(): String = label
}

/** Time the test moves by hand. */
internal class ManualClock(
    var now: Long = 1_000_000_000_000L,
) : MonotonicClock {
    override fun nanos(): Long = now

    fun advanceMillis(millis: Long) {
        now += millis * 1_000_000L
    }
}

/** One queued input, as the fake codec received it. */
internal class QueuedInput(
    val index: Int,
    val bytes: ByteArray,
    val presentationMicros: Long,
    val keyFrame: Boolean,
)

/** A `MediaCodec` stand-in that records every call; the test delivers its callbacks through [events]. */
internal class FakeCodec(
    override val name: String,
    private val events: CodecEvents<FakeSurface>,
    private val vendor: List<String>,
    private val inputCapacity: Int,
    private val failConfigureWhen: (DecoderFormat) -> Boolean,
) : VideoCodec<FakeSurface> {
    val configured = ArrayList<DecoderFormat>()
    var surface: FakeSurface? = null
    var started = false
    var released = false
    var detached = false
    var failQueueInput = false
    val inputs = ArrayList<QueuedInput>()

    /** Output index to (render time or null). */
    val outputs = ArrayList<Pair<Int, Long?>>()
    private val buffers = HashMap<Int, ByteBuffer>()

    override fun vendorParameters(): List<String> = vendor

    override fun configure(
        format: DecoderFormat,
        surface: FakeSurface,
    ) {
        check(!released) { "configure after release" }
        if (failConfigureWhen(format)) throw CodecCallException(failure("refused"))
        configured += format
        this.surface = surface
    }

    override fun start() {
        check(configured.isNotEmpty()) { "start before configure" }
        started = true
    }

    override fun inputBuffer(index: Int): ByteBuffer? {
        check(!released) { "inputBuffer after release" }
        return buffers.getOrPut(index) { ByteBuffer.allocate(inputCapacity) }.also { it.clear() }
    }

    override fun queueInput(
        index: Int,
        size: Int,
        presentationMicros: Long,
        keyFrame: Boolean,
    ) {
        check(!released) { "queueInput after release" }
        if (failQueueInput) throw CodecCallException(failure("queueInput failed"))
        val buffer = checkNotNull(buffers[index])
        val bytes = ByteArray(size).also { buffer.duplicate().apply { flip() }.get(it) }
        inputs += QueuedInput(index, bytes, presentationMicros, keyFrame)
    }

    override fun releaseOutput(
        index: Int,
        renderAtNanos: Long?,
    ) {
        check(!released) { "releaseOutput after release" }
        if (renderAtNanos != null) checkNotNull(surface) { "render without a surface" }
        outputs += index to renderAtNanos
    }

    override fun setOutputSurface(surface: FakeSurface) {
        check(!released) { "setOutputSurface after release" }
        this.surface = surface
        detached = false
    }

    override fun detachOutputSurface() {
        surface = null
        detached = true
    }

    override fun release() {
        released = true
        surface = null
    }

    // The callbacks MediaCodec would make, delivered as the decoder thread would.
    fun offerInput(index: Int) = events.onInputAvailable(this, index)

    fun emitOutput(
        index: Int,
        presentationMicros: Long,
    ) = events.onOutputAvailable(this, index, presentationMicros)

    fun emitRendered(
        presentationMicros: Long,
        nanos: Long,
    ) = events.onRendered(this, presentationMicros, nanos)

    fun emitError(failure: CodecFailure = CodecFailure(recoverable = false, transient = false, detail = "boom")) =
        events.onError(this, failure)
}

/** Decoders by name, and every codec created. */
internal class FakePlatform(
    var decoders: List<DecoderInfo> = listOf(hardwareDecoder("hw.avc")),
    var vendor: Map<String, List<String>> = emptyMap(),
    var inputCapacity: Int = 64 * 1024,
) : DecoderPlatform<FakeSurface> {
    val created = ArrayList<FakeCodec>()
    val createFails = HashSet<String>()
    var failConfigureWhen: (DecoderFormat) -> Boolean = { false }
    val frameRates = ArrayList<Pair<FakeSurface, Int>>()
    val catalogQueries = ArrayList<Triple<String, Int, Int>>()

    override val catalog =
        DecoderCatalog { mime, width, height ->
            catalogQueries += Triple(mime, width, height)
            decoders
        }

    override fun create(
        name: String,
        events: CodecEvents<FakeSurface>,
    ): VideoCodec<FakeSurface> {
        if (name in createFails) throw CodecCallException(failure("no $name"))
        return FakeCodec(name, events, vendor[name].orEmpty(), inputCapacity, failConfigureWhen).also { created += it }
    }

    override fun setFrameRate(
        surface: FakeSurface,
        framesPerSecond: Int,
    ) {
        frameRates += surface to framesPerSecond
    }

    val last: FakeCodec get() = created.last()

    /** Codecs not yet released. */
    val live: List<FakeCodec> get() = created.filter { !it.released }
}

internal fun hardwareDecoder(
    name: String,
    lowLatency: Boolean = false,
    detachedSurface: Boolean = false,
) = DecoderInfo(
    name = name,
    hardware = true,
    softwareOnly = false,
    alias = false,
    lowLatency = lowLatency,
    secureOnly = false,
    sizeSupported = true,
    detachedSurface = detachedSurface,
)

internal class RecordingListener : ScreenDecoder.Listener {
    var keyFrameRequests = 0
    var firstFrames = 0

    override fun onKeyFrameNeeded() {
        keyFrameRequests++
    }

    override fun onFirstFrameRendered() {
        firstFrames++
    }
}

/** Counts every frame handed back, by identity. */
internal class RecordingRecycler : ScreenDecoder.FrameRecycler {
    val recycled = ArrayList<ByteArray>()

    override fun recycle(frame: ByteArray) {
        recycled += frame
    }

    fun count(frame: ByteArray): Int = recycled.count { it === frame }
}

internal fun recordingLogger(sink: RecordingLogSink): Logger =
    testLogger(sink, TestTimeProvider(TestCoroutineScheduler()), "carplay")

/** Reads a test vector from `src/test/resources/video/`. */
internal fun vector(name: String): ByteArray =
    checkNotNull(VideoFakes::class.java.getResourceAsStream("/video/$name")) {
        "missing vector $name"
    }.use { it.readBytes() }

private object VideoFakes

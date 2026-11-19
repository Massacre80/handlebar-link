package com.abid.handlebarlink.carplay.engine.android.video

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import com.abid.handlebarlink.core.testing.RecordingLogSink
import org.junit.Test
import java.nio.ByteBuffer

class DecoderCoreTest {
    private val platform = FakePlatform()
    private val clock = ManualClock()
    private val recycler = RecordingRecycler()
    private val listener = RecordingListener()
    private val sink = RecordingLogSink()
    private val queue =
        FrameQueue(
            QueueLimits(
                maxPictures = 60,
                maxBytes = MAX_INPUT_SIZE.toLong(),
                maxAgeNanos = 250_000_000L,
                maxStartupAgeNanos = 1_500_000_000L,
            ),
            recycler,
            clock,
        )
    private val core =
        DecoderCore(
            platform,
            queue,
            listener,
            clock,
            recordingLogger(sink),
            statsIntervalNanos = 5_000_000_000L,
        )
    private val screen = FakeSurface("screen")
    private val avcc = vector("ffmpeg-64x64.avcc")
    private val csd0 = checkNotNull(AvcConfigParser.parse(avcc, 0, avcc.size)).csd0

    @Test
    fun `the decoder is prewarmed before the configuration, which then reaches it as a codec-config buffer`() {
        core.configure(Display(1280, 720, 60))
        core.attach(screen)
        val codec = platform.last
        val early = idr(1)
        submit(early)

        assertThat(codec.started).isTrue()
        assertThat(
            codec.configured
                .single()
                .csd0
                .toList(),
        ).isEmpty()
        assertThat(codec.surface).isSameInstanceAs(screen)
        assertThat(recycler.count(early)).isEqualTo(1) // no picture before the configuration
        assertThat(codec.inputs).isEmpty()

        submit(avcc, isConfig = true)
        codec.offerInput(0)
        codec.offerInput(1)
        val key = idr(2)
        submit(key)

        val config = checkNotNull(AvcConfigParser.parse(avcc, 0, avcc.size))
        assertThat(codec.csdInputs.single().toList()).isEqualTo((config.csd0 + config.csd1).toList())
        assertThat(platform.created).containsExactly(codec)
        assertThat(
            codec.inputs
                .single()
                .bytes
                .toList(),
        ).isEqualTo(annexB(key))
        assertThat(codec.inputs.single().keyFrame).isTrue()
        assertThat(recycler.count(key)).isEqualTo(1)
        val started =
            sink
                .events("carplay.video.decoder")
                .single()
                .fields
                .toMap()
        assertThat(started["prewarmed"]).isEqualTo(true)
        assertThat(started["start_ms"]).isEqualTo(0L)
    }

    @Test
    fun `a slow decoder start loses no picture from the first keyframe on`() {
        // The Poco, 2026-11-19: a cold start held the decoder thread about 200 ms (12 pictures at 60 fps); a 6-picture
        // limit dropped the first keyframe and the screen stayed black until the next one.
        core.configure(Display(1280, 720, 60))
        core.attach(screen)
        val codec = platform.last
        submit(avcc, isConfig = true)
        val pictures = listOf(idr(0)) + List(11) { p(it + 1) }
        pictures.forEach { submit(it) } // the codec has not handed out an input yet

        repeat(pictures.size + 1) { codec.offerInput(it) }

        assertThat(codec.csdInputs.size).isEqualTo(1)
        assertThat(codec.inputs.map { it.bytes.toList() }).isEqualTo(pictures.map { annexB(it) })
        assertThat(listener.keyFrameRequests).isEqualTo(0)
    }

    @Test
    fun `a decoder that supports a detached surface is prewarmed before there is a surface`() {
        platform.decoders = listOf(hardwareDecoder("hw.detach", detachedSurface = true))

        core.configure(Display(1280, 720, 60))
        val codec = platform.last
        core.attach(screen)

        assertThat(
            codec.configured
                .single()
                .csd0
                .toList(),
        ).isEmpty()
        assertThat(codec.surface).isSameInstanceAs(screen)
        assertThat(codec.detached).isFalse()
        assertThat(platform.created).containsExactly(codec)
    }

    @Test
    fun `without a surface a decoder that needs one is not prewarmed`() {
        core.configure(Display(1280, 720, 60))

        assertThat(platform.created).isEmpty()
    }

    @Test
    fun `a refused detached prewarm starts the decoder at the surface without waiting`() {
        platform.decoders = listOf(hardwareDecoder("hw.detach", detachedSurface = true))
        platform.refuseDetached = true
        core.configure(Display(1280, 720, 60))
        assertThat(platform.live).isEmpty()

        core.attach(screen)

        assertThat(core.decoderName).isEqualTo("hw.detach")
        assertThat(platform.last.surface).isSameInstanceAs(screen)
        assertThat(sink.events("carplay.video.decoder_unavailable")).isEmpty()
    }

    @Test
    fun `a new decoder is fed from a keyframe on, and asks for one at most once a second`() {
        val codec = started()
        codec.offerInput(0)
        codec.offerInput(1)

        submit(p(1))
        submit(p(2))
        assertThat(listener.keyFrameRequests).isEqualTo(1)
        clock.advanceMillis(1_001)
        submit(p(3))
        assertThat(listener.keyFrameRequests).isEqualTo(2)
        val key = idr(4)
        val next = p(5)
        submit(key)
        submit(next)

        assertThat(codec.inputs.map { it.bytes.toList() }).containsExactly(annexB(key), annexB(next))
    }

    @Test
    fun `pictures queued before a new configuration go to the old decoder, later ones to the new`() {
        val old = started()
        old.offerInput(0)
        val first = idr(1)
        submit(first)
        val second = p(2)
        val other = otherConfig()
        val third = p(3)
        queue.submit(second, 0, second.size, 0, isConfig = false)
        queue.submit(other, 0, other.size, 0, isConfig = true)
        queue.submit(third, 0, third.size, 0, isConfig = false)
        core.pump()

        assertThat(platform.created).containsExactly(old) // the config waits behind the picture before it

        old.offerInput(1)
        val replacement = platform.last
        replacement.offerInput(0)
        val fourth = idr(4)
        submit(fourth)

        assertThat(old.inputs.map { it.bytes.toList() }).containsExactly(annexB(first), annexB(second))
        assertThat(old.released).isTrue()
        assertThat(
            replacement.configured
                .single()
                .csd0
                .toList(),
        ).isEqualTo(checkNotNull(AvcConfigParser.parse(other, 0, other.size)).csd0.toList())
        assertThat(replacement.inputs.map { it.bytes.toList() }).containsExactly(annexB(fourth)) // third: no keyframe
    }

    @Test
    fun `after the queue overflowed the decoder waits for the next keyframe and asks for one`() {
        val codec = started()
        codec.offerInput(0)
        val first = idr(0)
        submit(first)
        codec.emitOutput(0, codec.inputs.single().presentationMicros) // running: the 250 ms age limit applies
        submit(p(1)) // no free input: it waits
        clock.advanceMillis(260)
        submit(p(2)) // the oldest waited too long: both are dropped and the chain is broken

        codec.offerInput(1)
        submit(p(10))
        val key = idr(11)
        submit(key)

        assertThat(codec.inputs.map { it.bytes.toList() }).containsExactly(annexB(first), annexB(key))
        assertThat(listener.keyFrameRequests).isEqualTo(1)
    }

    @Test
    fun `a backlog left by a slow start drains without a drop`() {
        // The Poco, run 2 (2026-11-19): 14 pictures waited for the prewarmed decoder; once its first output came, a
        // strict running limit broke the chain while they drained (dropped_full=11, dropped_wait_key=43).
        core.configure(Display(1280, 720, 60))
        core.attach(screen)
        val codec = platform.last
        submit(avcc, isConfig = true)
        val sent = ArrayList<ByteArray>()
        repeat(14) {
            sent += if (it == 0) idr(0) else p(it)
            submit(sent.last())
            clock.advanceMillis(16)
        }
        var input = 0
        codec.offerInput(input++) // the codec-config buffer
        codec.offerInput(input++) // the IDR
        clock.advanceMillis(40)
        codec.emitOutput(0, codec.inputs.single().presentationMicros) // first output: 13 still wait, 250 ms and more

        repeat(16) {
            clock.advanceMillis(16)
            sent += p(100 + it)
            submit(sent.last())
            codec.offerInput(input++) // the first decodes are slow: two inputs per new picture
            codec.offerInput(input++)
        }

        assertThat(codec.inputs.map { it.bytes.toList() }).isEqualTo(sent.map { annexB(it) })
        assertThat(listener.keyFrameRequests).isEqualTo(0)
        assertThat(queue.takeCounts().droppedFull).isEqualTo(0)
    }

    @Test
    fun `an unchanged configuration keeps the running decoder`() {
        val codec = started()

        submit(avcc.copyOf(), isConfig = true)

        assertThat(platform.created).containsExactly(codec)
        assertThat(codec.released).isFalse()
    }

    @Test
    fun `a decoder error starts a new decoder from the configuration and asks for a keyframe`() {
        val old = started()
        old.offerInput(0)
        submit(idr(1))

        old.emitError()
        val fresh = platform.last
        // Late callbacks of the released decoder must not reach it (FakeCodec throws on any call after release).
        old.offerInput(1)
        old.emitOutput(0, old.inputs.single().presentationMicros)
        fresh.offerInput(0)
        submit(p(2))
        val key = idr(3)
        submit(key)

        assertThat(old.released).isTrue()
        assertThat(fresh).isNotSameInstanceAs(old)
        assertThat(
            fresh.configured
                .single()
                .csd0
                .toList(),
        ).isEqualTo(csd0.toList())
        assertThat(listener.keyFrameRequests).isEqualTo(1)
        assertThat(fresh.inputs.map { it.bytes.toList() }).containsExactly(annexB(key))
        assertThat(sink.events("carplay.video.decoder_error")).isNotEmpty()
    }

    @Test
    fun `a call that throws recovers like an error callback`() {
        val old = started()
        old.offerInput(0)
        old.failQueueInput = true
        val key = idr(1)

        submit(key)

        assertThat(old.released).isTrue()
        assertThat(platform.live).containsExactly(platform.last)
        assertThat(recycler.count(key)).isEqualTo(1)
    }

    @Test
    fun `a decoder that fails twice before its first output gives way to the next one`() {
        platform.decoders = listOf(hardwareDecoder("first"), hardwareDecoder("second"))
        started()

        platform.last.emitError()
        clock.advanceMillis(10)
        platform.last.emitError()

        assertThat(platform.created.map { it.name }).containsExactly("first", "first", "second")
        assertThat(core.decoderName).isEqualTo("second")
    }

    @Test
    fun `when no decoder starts it waits a second before trying again`() {
        platform.createFails += "hw.avc"
        start()
        submit(idr(1))
        assertThat(core.decoderName).isNull()
        assertThat(sink.events("carplay.video.decoder_unavailable")).isNotEmpty()

        platform.createFails.clear()
        clock.advanceMillis(500)
        core.tick()
        assertThat(platform.created).isEmpty()
        clock.advanceMillis(600)
        core.tick()

        assertThat(core.decoderName).isEqualTo("hw.avc")
        assertThat(listener.keyFrameRequests).isEqualTo(1)
    }

    @Test
    fun `errors that keep coming are retried no faster than once a second`() {
        started()
        repeat(4) {
            // Each decoder shows a picture before failing, so none is skipped for another.
            val codec = platform.last
            codec.offerInput(0)
            submit(idr(it))
            codec.emitOutput(0, codec.inputs.single().presentationMicros)
            clock.advanceMillis(10)
            codec.emitError()
        }

        clock.advanceMillis(10)
        core.tick()

        assertThat(platform.created.size).isEqualTo(4)
        assertThat(core.decoderName).isNull()

        clock.advanceMillis(1_000)
        core.tick()

        assertThat(platform.created.size).isEqualTo(5)
    }

    @Test
    fun `a refused tuned format falls back to the plain one`() {
        platform.failConfigureWhen = { it.realtime }

        val codec = started()

        assertThat(codec.configured.single().realtime).isFalse()
        assertThat(platform.created.first().released).isTrue()
        assertThat(sink.events("carplay.video.configure_failed")).isNotEmpty()
    }

    @Test
    fun `outputs are shown at once with the current time as their render time`() {
        val codec = started()
        codec.offerInput(0)
        submit(idr(1))
        clock.advanceMillis(7)

        codec.emitOutput(3, codec.inputs.single().presentationMicros)

        assertThat(codec.outputs).containsExactly(3 to clock.now)
    }

    @Test
    fun `losing the surface releases the decoder, and a new surface gets a new decoder and a keyframe`() {
        val codec = started()
        codec.offerInput(0)
        submit(idr(1))

        core.detach()
        val whileAway = idr(2)
        submit(whileAway)

        assertThat(codec.released).isTrue()
        assertThat(platform.created).containsExactly(codec)
        assertThat(recycler.count(whileAway)).isEqualTo(1)

        val back = FakeSurface("back")
        core.attach(back)

        assertThat(platform.last.surface).isSameInstanceAs(back)
        assertThat(platform.live.size).isEqualTo(1)
        assertThat(listener.keyFrameRequests).isEqualTo(1)
    }

    @Test
    fun `a decoder that supports a detached surface keeps decoding without drawing`() {
        platform.decoders = listOf(hardwareDecoder("hw.detach", detachedSurface = true))
        val codec = started()
        codec.offerInput(0)
        codec.offerInput(1)
        submit(idr(1))

        core.detach()
        submit(p(2))
        codec.emitOutput(0, codec.inputs[0].presentationMicros)

        assertThat(codec.released).isFalse()
        assertThat(codec.detached).isTrue()
        assertThat(codec.inputs.size).isEqualTo(2)
        assertThat(codec.outputs).containsExactly(0 to null)

        val back = FakeSurface("back")
        core.attach(back)
        codec.emitOutput(1, codec.inputs[1].presentationMicros)

        assertThat(codec.surface).isSameInstanceAs(back)
        assertThat(codec.outputs.last()).isEqualTo(1 to clock.now)
        assertThat(platform.created).containsExactly(codec)
    }

    @Test
    fun `a picture larger than the input buffer makes the next decoder ask for the largest input`() {
        platform.inputCapacity = 16
        val codec = started()
        codec.offerInput(0)

        submit(idr(1, size = 32))

        assertThat(codec.released).isTrue()
        assertThat(
            platform.last.configured
                .single()
                .maxInputSize,
        ).isEqualTo(MAX_INPUT_SIZE)
    }

    @Test
    fun `release frees the decoder and hands back every waiting picture`() {
        val codec = started()
        val waiting = idr(1)
        submit(waiting) // no input buffer yet: it waits

        core.release()
        core.attach(FakeSurface("later"))
        submit(idr(2))

        assertThat(codec.released).isTrue()
        assertThat(recycler.count(waiting)).isEqualTo(1)
        assertThat(platform.created).containsExactly(codec)
    }

    @Test
    fun `the display frame rate goes to the surface`() {
        started()

        assertThat(platform.frameRates).containsExactly(screen to 60)
    }

    @Test
    fun `the stats line reports frames, drops and latency from submit to decode and to display`() {
        val codec = started()
        core.tick() // starts the window
        codec.offerInput(0)
        submit(p(0)) // dropped: waiting for a keyframe
        submit(idr(1))
        val presentation = codec.inputs.single().presentationMicros
        clock.advanceMillis(10)
        codec.emitOutput(0, presentation)
        clock.advanceMillis(20)
        codec.emitRendered(presentation, clock.now)
        clock.advanceMillis(5_000)

        core.tick()

        val stats =
            sink
                .events("carplay.video.stats")
                .single()
                .fields
                .toMap()
        assertThat(stats["decoder"]).isEqualTo("hw.avc")
        assertThat(stats["received"]).isEqualTo(2)
        assertThat(stats["decoded"]).isEqualTo(1)
        assertThat(stats["shown"]).isEqualTo(1)
        assertThat(stats["rendered"]).isEqualTo(1)
        assertThat(stats["dropped_wait_key"]).isEqualTo(1)
        assertThat(stats["decode_p50_ms"]).isEqualTo(10.0)
        assertThat(stats["render_p90_ms"]).isEqualTo(30.0)
        assertThat(listener.firstFrames).isEqualTo(1)
        // The same window reaches the app; a wait for a keyframe is not a picture lost on the way.
        assertThat(listener.stats.single()).isEqualTo(
            CarPlayVideoStats(
                received = 2,
                shown = 1,
                framesPerSecond = 0.2,
                dropped = 0,
                decodeP90Millis = 10.0,
                recoveries = 0,
            ),
        )
    }

    /** A running decoder: display, surface and configuration given. */
    private fun started(): FakeCodec {
        start()
        return platform.last.also { assertThat(it.started).isTrue() }
    }

    /**
     * Gives the display, the surface and the configuration. The decoder is prewarmed at the surface; it gets the
     * configuration as a codec-config buffer in input [CSD_INPUT].
     */
    private fun start() {
        core.configure(Display(1280, 720, 60))
        core.attach(screen)
        submit(avcc, isConfig = true)
        platform.live
            .lastOrNull()
            ?.takeIf {
                it.configured
                    .last()
                    .csd0
                    .isEmpty()
            }?.offerInput(CSD_INPUT)
    }

    private fun submit(
        frame: ByteArray,
        isConfig: Boolean = false,
    ) {
        queue.submit(frame, 0, frame.size, 0, isConfig)
        core.pump()
    }

    /** Annex B of a 4-byte length-prefixed picture with one NAL unit, by hand. */
    private fun annexB(lengthPrefixed: ByteArray): List<Byte> = listOf<Byte>(0, 0, 0, 1) + lengthPrefixed.drop(4)

    /** Another stream's avcC: the same record with the SPS's level byte changed (level_idc 10 to 31). */
    private fun otherConfig(): ByteArray = avcc.copyOf().also { it[11] = 31 }

    @Test
    fun `the test's own config differs where it should`() {
        assertThat(otherConfig()[11]).isEqualTo(31.toByte())
        assertThat(avcc[11]).isEqualTo(10.toByte()) // ffmpeg-64x64.avcc: 67 64 00 0a, level 1.0
        assertThat(ByteBuffer.wrap(otherConfig()).get(8)).isEqualTo(0x67.toByte())
        assertThat(AvcConfigParser.parse(otherConfig(), 0, avcc.size)).isNotNull()
    }

    private companion object {
        const val CSD_INPUT = 100
    }
}

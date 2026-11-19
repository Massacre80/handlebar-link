package com.abid.handlebarlink.carplay.engine.android.video

import android.app.Instrumentation
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import com.abid.handlebarlink.core.common.log.LogEntry
import com.abid.handlebarlink.core.common.log.LogSink
import com.abid.handlebarlink.core.common.log.Logfmt
import com.abid.handlebarlink.core.common.log.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the ffmpeg test clip (`src/test/resources/video/README.md`) through the real [ScreenDecoder] and the phone's hardware
 * decoder, at the iPhone's pace (60 fps), into an `ImageReader` surface, and reports the `carplay.video.*` lines.
 * Not part of `check` (no device there); the coordinator runs it on the Poco:
 * `adb shell am instrument -w -r -e class com.abid.handlebarlink.carplay.engine.android.video.ScreenDecoderSmokeTest \
 *   com.abid.handlebarlink.carplay.engine.android.test/androidx.test.runner.AndroidJUnitRunner`.
 */
class ScreenDecoderSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val clip by lazy {
        instrumentation.context.assets
            .open(CLIP)
            .use { it.readBytes() }
    }

    @Test
    fun annexBStreamPlaysOnTheHardwareDecoder() = play(lengthPrefixed = false)

    @Test
    fun lengthPrefixedStreamWithAvcCLikeTheIphonePlays() = play(lengthPrefixed = true)

    private fun play(lengthPrefixed: Boolean) {
        val units = accessUnits(clip)
        val nals = units.flatten()
        val sps = nals.first { it.type() == SPS }
        val pps = nals.first { it.type() == PPS }
        val config = if (lengthPrefixed) avcC(sps, pps) else annexB(listOf(sps, pps))
        val pictures =
            units
                .map { unit -> unit.filter { it.type() !in setOf(SPS, PPS, AUD) } }
                .filter { it.isNotEmpty() }
                .map { if (lengthPrefixed) lengthPrefix(it) else annexB(it) }

        val entries = CopyOnWriteArrayList<LogEntry>()
        val logger =
            Logger("carplay", Clock.systemUTC(), listOf(LogSink { entries += it }, StatusSink(instrumentation)))
        val keyFrameRequests = AtomicInteger()
        val readerThread = HandlerThread("smoke-reader").apply { start() }
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, READER_IMAGES)
        val images = AtomicInteger()
        reader.setOnImageAvailableListener({
            it.acquireLatestImage()?.close()
            images.incrementAndGet()
        }, Handler(readerThread.looper))
        val decoder =
            createScreenDecoder(
                logger,
                object : ScreenDecoder.Listener {
                    override fun onKeyFrameNeeded() {
                        keyFrameRequests.incrementAndGet()
                    }
                },
                options = ScreenDecoder.Options(statsIntervalMillis = STATS_MILLIS),
            )
        try {
            decoder.configure(WIDTH, HEIGHT, FPS)
            decoder.attach(reader.surface)
            decoder.submit(config, 0, config.size, 0, isConfig = true)
            pictures.forEachIndexed { index, picture ->
                decoder.submit(picture, 0, picture.size, index * FRAME_MICROS, isConfig = false)
                Thread.sleep(FRAME_MILLIS) // the iPhone's pace, about 60 fps
            }
            Thread.sleep(SETTLE_MILLIS) // the last stats window ends
            decoder.detach()
        } finally {
            decoder.release()
            reader.close()
            readerThread.quitSafely()
        }

        val stats = entries.filter { it.event == "carplay.video.stats" }.map { it.fields.toMap() }
        val decoded = stats.sumOf { it["decoded"] as Int }
        val recoveries = stats.sumOf { it["recoveries"] as Int }
        report(
            "pictures=${pictures.size} decoded=$decoded images=${images.get()} keyframe_requests=${keyFrameRequests.get()}",
        )
        assertTrue("a decoder started", entries.any { it.event == "carplay.video.decoder" })
        assertEquals("decoder errors", 0, recoveries)
        assertTrue("decoded $decoded of ${pictures.size}", decoded >= pictures.size * MIN_DECODED_PERCENT / PERCENT)
    }

    private fun report(line: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$line\n") })
    }

    /** Every log line goes to `am instrument -r` as a status stream line. */
    private class StatusSink(
        private val instrumentation: Instrumentation,
    ) : LogSink {
        private val format = Logfmt(ZoneOffset.UTC)

        override fun write(entry: LogEntry) {
            val line = format.render(entry) + "\n"
            instrumentation.sendStatus(0, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, line) })
        }
    }

    private companion object {
        const val CLIP = "video/testsrc2-1280x720-60fps-5s.h264"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 60
        const val FRAME_MICROS = 16_667L
        const val FRAME_MILLIS = 16L
        const val SETTLE_MILLIS = 1_500L
        const val STATS_MILLIS = 1_000L
        const val READER_IMAGES = 4
        const val MIN_DECODED_PERCENT = 90
        const val PERCENT = 100
        const val SPS = 7
        const val PPS = 8
        const val AUD = 9

        fun ByteArray.type(): Int = this[0].toInt() and 0x1f

        /** NAL units without start codes, grouped into access units at each access unit delimiter (ffmpeg `aud=insert`). */
        fun accessUnits(stream: ByteArray): List<List<ByteArray>> {
            val units = ArrayList<MutableList<ByteArray>>()
            nals(stream).forEach { nal ->
                if (nal.type() == AUD || units.isEmpty()) units += ArrayList<ByteArray>()
                units.last() += nal
            }
            return units
        }

        /** Splits Annex B at its start codes (the test's own splitter, independent of `Avc`). */
        fun nals(stream: ByteArray): List<ByteArray> {
            val starts = ArrayList<Pair<Int, Int>>()
            var i = 0
            while (i + 3 <= stream.size) {
                if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
                    starts += (if (i > 0 && stream[i - 1].toInt() == 0) i - 1 else i) to i + 3
                    i += 3
                } else {
                    i++
                }
            }
            return starts.mapIndexed { n, (_, payload) ->
                stream.copyOfRange(payload, if (n + 1 < starts.size) starts[n + 1].first else stream.size)
            }
        }

        fun annexB(nals: List<ByteArray>): ByteArray =
            nals.fold(ByteArray(0)) { acc, nal ->
                acc +
                    byteArrayOf(0, 0, 0, 1) +
                    nal
            }

        fun lengthPrefix(nals: List<ByteArray>): ByteArray =
            nals.fold(ByteArray(0)) { acc, nal ->
                acc +
                    byteArrayOf(
                        (nal.size ushr 24).toByte(),
                        (nal.size ushr 16).toByte(),
                        (nal.size ushr 8).toByte(),
                        nal.size.toByte(),
                    ) +
                    nal
            }

        /** An avcC record (ISO/IEC 14496-15 §5.3.3.1) with 4-byte lengths, as the iPhone sends it. */
        fun avcC(
            sps: ByteArray,
            pps: ByteArray,
        ): ByteArray =
            byteArrayOf(
                1,
                sps[1],
                sps[2],
                sps[3],
                0xFF.toByte(),
                0xE1.toByte(),
                (sps.size ushr 8).toByte(),
                sps.size.toByte(),
            ) +
                sps + byteArrayOf(1, (pps.size ushr 8).toByte(), pps.size.toByte()) + pps
    }
}

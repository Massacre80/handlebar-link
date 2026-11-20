// UnconfinedTestDispatcher, so the collector runs as each request is emitted.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.android.video

import android.view.Surface
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The engine's screen port on the decoder. The `avcC` and its Annex B form come from ffmpeg (`ffmpeg-64x64.avcc` and
 * the SPS/PPS that `h264_mp4toannexb` wrote into `ffmpeg-64x64.h264`, resources/video/README.md), and the C3 stream
 * vector (an iPhone-like High profile record, MediaReceiversTest.AVCC) read by hand from ISO/IEC 14496-15 §5.3.3.1.
 */
class AndroidScreenPortTest {
    private class Submitted(
        val bytes: String,
        val ptsMicros: Long,
        val isConfig: Boolean,
    )

    private class RecordingDecoder(
        val listener: ScreenDecoder.Listener,
        val recycler: ScreenDecoder.FrameRecycler,
    ) : ScreenDecoder {
        val calls = ArrayList<String>()
        val submitted = ArrayList<Submitted>()

        override fun configure(
            width: Int,
            height: Int,
            framesPerSecond: Int,
        ) {
            calls += "configure ${width}x$height@$framesPerSecond"
        }

        override fun attach(surface: Surface) {
            calls += "attach"
        }

        override fun detach() {
            calls += "detach"
        }

        override fun submit(
            frame: ByteArray,
            offset: Int,
            length: Int,
            ptsMicros: Long,
            isConfig: Boolean,
        ): Boolean {
            submitted += Submitted(frame.copyOfRange(offset, offset + length).toHex(), ptsMicros, isConfig)
            return true
        }

        override fun release() {
            calls += "release"
        }
    }

    private fun send(
        isConfig: Boolean,
        senderNanos: Long,
        payload: ByteArray,
    ) = port.frame(isConfig, senderNanos, payload, payload.size)

    private lateinit var decoder: RecordingDecoder
    private val port =
        AndroidScreenPort { listener, recycler -> RecordingDecoder(listener, recycler).also { decoder = it } }

    @Test
    fun `the decoder's numbers reach the screen's stats until a new stream starts`() {
        val window = CarPlayVideoStats(300, 298, 59.6, 2, 12.5, 0)
        decoder.listener.onStats(window)
        assertThat(port.stats.value).isEqualTo(window)

        port.configure(1280, 720, 60)

        assertThat(port.stats.value).isNull()
    }

    @Test
    fun `the stream SETUP configures the decoder with the canvas`() {
        port.configure(1080, 2246, 60)

        assertThat(decoder.calls).containsExactly("configure 1080x2246@60")
    }

    @Test
    fun `an avcC with 4-byte lengths goes in as its SPS and PPS behind start codes, as ffmpeg writes them`() {
        val avcc = resource("ffmpeg-64x64.avcc")
        val annexB = resource("ffmpeg-64x64.h264").toHex()
        val sps = "000000016764000aacb42136022000000300200000" + "0f01e244d4"
        val pps = "0000000168ef0fcb"

        send(isConfig = true, senderNanos = 0L, payload = avcc)

        // ffmpeg's own Annex B stream carries exactly these two parameter sets.
        assertThat(annexB.contains(sps) && annexB.contains(pps)).isTrue()
        assertThat(decoder.submitted.single().bytes).isEqualTo(sps + pps)
        assertThat(decoder.submitted.single().isConfig).isTrue()
    }

    @Test
    fun `the iPhone-like avcC becomes SPS and PPS, and pictures go in as they came with their time in microseconds`() {
        send(isConfig = true, senderNanos = 0L, payload = hex(IPHONE_AVCC))
        send(isConfig = false, senderNanos = 3_250_000_000L, payload = hex(ANNEX_B_PICTURE))

        assertThat(decoder.submitted.map { it.bytes })
            .containsExactly("000000016764001f" + "0000000168ee3c80", ANNEX_B_PICTURE)
        assertThat(decoder.submitted.map { it.ptsMicros }).containsExactly(0L, 3_250_000L)
        assertThat(decoder.submitted.map { it.isConfig }).containsExactly(true, false)
    }

    @Test
    fun `a picture goes in as the first bytes of its buffer, which the decoder gives back for reuse`() {
        val picture = hex(ANNEX_B_PICTURE)
        val buffer = port.pictureBuffer(picture.size + 16)
        picture.copyInto(buffer)

        port.frame(isConfig = false, senderNanos = 0L, payload = buffer, length = picture.size)
        decoder.recycler.recycle(buffer)

        assertThat(decoder.submitted.single().bytes).isEqualTo(ANNEX_B_PICTURE)
        assertThat(port.pictureBuffer(picture.size + 16)).isSameInstanceAs(buffer)
        assertThat(port.buffers.allocated).isEqualTo(1)
    }

    @Test
    fun `an avcC with 2-byte lengths and an unreadable record go in unchanged`() {
        // lengthSizeMinusOne 1: the reader left those pictures length-prefixed, so the decoder must read them so.
        val twoByte = "0164001ffde100046764001f01000468ee3c80"

        send(isConfig = true, senderNanos = 0L, payload = hex(twoByte))
        send(isConfig = true, senderNanos = 0L, payload = hex("02ff"))

        assertThat(decoder.submitted.map { it.bytes }).containsExactly(twoByte, "02ff")
    }

    @Test
    fun `the decoder's key frame requests reach a listening engine, and are dropped when none listens`() =
        runTest {
            decoder.listener.onKeyFrameNeeded() // nobody listens: dropped, not delivered later
            var received = 0
            val listening =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    port.keyFrameRequests.collect { received++ }
                }

            decoder.listener.onKeyFrameNeeded()
            decoder.listener.onKeyFrameNeeded()

            assertThat(received).isEqualTo(2)
            listening.cancel()
        }

    private companion object {
        /** C3 stream vector (generate_vectors.py): High 3.1, lengthSizeMinusOne 3, SPS 6764001f, PPS 68ee3c80. */
        const val IPHONE_AVCC = "0164001fffe100046764001f01000468ee3c80"

        /** An Annex B access unit as the screen stream reader returns it (C3 vector): SEI-less IDR and a slice. */
        const val ANNEX_B_PICTURE = "00000001651100000001419a02"

        fun resource(name: String): ByteArray =
            checkNotNull(AndroidScreenPortTest::class.java.getResourceAsStream("/video/$name")).use { it.readBytes() }

        fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

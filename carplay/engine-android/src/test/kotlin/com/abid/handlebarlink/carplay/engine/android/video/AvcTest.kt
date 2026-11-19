package com.abid.handlebarlink.carplay.engine.android.video

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The H.264 handling against ffmpeg, an independent implementation (docs/LESSONS.md 46): the `avcC` record and the
 * length-prefixed samples come from ffmpeg's MP4 muxer, the Annex B stream from ffmpeg's `h264_mp4toannexb`, and
 * ffprobe says frame 0 is a keyframe (I) and frame 1 is not (P). How they were made:
 * `src/test/resources/video/README.md`.
 */
class AvcTest {
    private val avcc = vector("ffmpeg-64x64.avcc")
    private val annexB = vector("ffmpeg-64x64.h264")
    private val frame0 = vector("ffmpeg-64x64-frame0.lp")
    private val frame1 = vector("ffmpeg-64x64-frame1.lp")

    @Test
    fun `the avcC record gives the SPS and PPS that ffmpeg writes into its Annex B stream`() {
        val config = checkNotNull(AvcConfigParser.parse(avcc, 0, avcc.size))

        assertThat(config.csd0.toList()).isEqualTo(withStartCode(annexBNals(annexB).single { it.type() == SPS }))
        assertThat(config.csd1.toList()).isEqualTo(withStartCode(annexBNals(annexB).single { it.type() == PPS }))
        assertThat(config.nalLengthSize).isEqualTo(4)
    }

    @Test
    fun `an Annex B configuration gives the same CSD and Annex B framing`() {
        val fromAvcc = checkNotNull(AvcConfigParser.parse(avcc, 0, avcc.size))

        val fromAnnexB = checkNotNull(AvcConfigParser.parse(annexB, 0, annexB.size))

        assertThat(fromAnnexB.csd0.toList()).isEqualTo(fromAvcc.csd0.toList())
        assertThat(fromAnnexB.csd1.toList()).isEqualTo(fromAvcc.csd1.toList())
        assertThat(fromAnnexB.nalLengthSize).isEqualTo(AvcConfig.ANNEX_B)
    }

    @Test
    fun `a config inside a larger buffer is read from its offset`() {
        val padded = ByteArray(5) + avcc + ByteArray(3)

        val config = AvcConfigParser.parse(padded, 5, avcc.size)

        assertThat(config?.csd0?.toList()).isEqualTo(AvcConfigParser.parse(avcc, 0, avcc.size)?.csd0?.toList())
    }

    @Test
    fun `cut short or foreign configs are refused`() {
        assertThat(AvcConfigParser.parse(avcc, 0, 20)).isNull()
        assertThat(AvcConfigParser.parse(byteArrayOf(2, 0x64, 0, 10, -1, -31), 0, 6)).isNull()
        assertThat(AvcConfigParser.parse(ByteArray(0), 0, 0)).isNull()
        // An Annex B stream with pictures only.
        assertThat(AvcConfigParser.parse(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), 0, 7)).isNull()
    }

    @Test
    fun `a length-prefixed sample becomes the NAL units ffmpeg writes for it, behind 4-byte start codes`() {
        val out = ByteBuffer.allocate(4096)

        val written = Avc.writeAnnexB(frame0, 0, frame0.size, 4, out)

        val ours = out.array().copyOf(written)
        val ffmpegNals = annexBNals(annexB).filter { it.type() != SPS && it.type() != PPS }
        assertThat(annexBNals(ours)).isEqualTo(ffmpegNals.take(annexBNals(ours).size))
        assertThat(ours.take(4)).containsExactly(0.toByte(), 0.toByte(), 0.toByte(), 1.toByte())
        assertThat(written).isEqualTo(frame0.size) // 4-byte lengths become 4-byte start codes
    }

    @Test
    fun `keyframes are found by the first slice`() {
        assertThat(Avc.isIdr(frame0, 0, frame0.size, 4)).isTrue()
        assertThat(Avc.isIdr(frame1, 0, frame1.size, 4)).isFalse()
        assertThat(Avc.isIdr(annexB, 0, annexB.size, AvcConfig.ANNEX_B)).isTrue()
    }

    @Test
    fun `pictures whose lengths do not add up are rejected and nothing is written`() {
        val broken = frame1.copyOf().also { it[3] = (it[3] + 1).toByte() }
        val out = ByteBuffer.allocate(4096)

        assertThat(Avc.writeAnnexB(broken, 0, broken.size, 4, out)).isEqualTo(Avc.MALFORMED)
        assertThat(out.position()).isEqualTo(0)
        assertThat(Avc.isIdr(broken, 0, broken.size, 4)).isFalse()
    }

    @Test
    fun `a picture larger than the input buffer is reported, not cut`() {
        val out = ByteBuffer.allocate(frame0.size - 1)

        assertThat(Avc.writeAnnexB(frame0, 0, frame0.size, 4, out)).isEqualTo(Avc.TOO_LARGE)
        assertThat(out.position()).isEqualTo(0)
    }

    @Test
    fun `an Annex B picture is copied as it is`() {
        val out = ByteBuffer.allocate(4096)

        val written = Avc.writeAnnexB(annexB, 0, annexB.size, AvcConfig.ANNEX_B, out)

        assertThat(out.array().copyOf(written).toList()).isEqualTo(annexB.toList())
    }

    @Test
    fun `2-byte length prefixes from the config are honoured`() {
        val nal = byteArrayOf(0x65, 1, 2, 3)
        val picture = byteArrayOf(0, 4) + nal
        val out = ByteBuffer.allocate(64)

        val written = Avc.writeAnnexB(picture, 0, picture.size, 2, out)

        assertThat(out.array().copyOf(written).toList()).isEqualTo(listOf<Byte>(0, 0, 0, 1) + nal.toList())
        assertThat(Avc.isIdr(picture, 0, picture.size, 2)).isTrue()
        assertThat(AvcConfigParser.parse(avcc.copyOf().also { it[4] = 0xFD.toByte() }, 0, avcc.size)).isNotNull()
    }

    private fun withStartCode(nal: List<Byte>): List<Byte> = listOf<Byte>(0, 0, 0, 1) + nal

    private fun List<Byte>.type(): Int = this[0].toInt() and 0x1f

    private companion object {
        const val SPS = 7
        const val PPS = 8
    }
}

/**
 * Splits an Annex B stream at its start codes; the test's own splitter, independent of [Avc] (trailing zero bytes of a
 * 4-byte start code are dropped from the NAL unit before it).
 */
internal fun annexBNals(stream: ByteArray): List<List<Byte>> {
    val starts = ArrayList<Pair<Int, Int>>() // (code position, payload start)
    var i = 0
    while (i + 3 <= stream.size) {
        if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
            val codeAt = if (i > 0 && stream[i - 1].toInt() == 0) i - 1 else i
            starts += codeAt to i + 3
            i += 3
        } else {
            i++
        }
    }
    return starts.mapIndexed { n, (_, payload) ->
        val end = if (n + 1 < starts.size) starts[n + 1].first else stream.size
        stream.copyOfRange(payload, end).toList()
    }
}

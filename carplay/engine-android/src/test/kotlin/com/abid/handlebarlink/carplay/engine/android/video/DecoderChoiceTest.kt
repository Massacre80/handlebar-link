package com.abid.handlebarlink.carplay.engine.android.video

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class DecoderChoiceTest {
    private val software =
        DecoderInfo(
            name = "c2.android.avc.decoder",
            hardware = false,
            softwareOnly = true,
            alias = false,
            lowLatency = true,
            secureOnly = false,
            sizeSupported = true,
            detachedSurface = false,
        )

    @Test
    fun `a hardware decoder that advertises low latency comes before the platform's first choice`() {
        // DiPlay's Tang lists c2.qti.avc.decoder first; c2.qti.avc.decoder.low_latency advertises FEATURE_LowLatency.
        val default = hardwareDecoder("c2.qti.avc.decoder")
        val lowLatency = hardwareDecoder("c2.qti.avc.decoder.low_latency", lowLatency = true)

        val order = chooseDecoders(listOf(default, lowLatency))

        assertThat(order.map { it.name }).containsExactly("c2.qti.avc.decoder.low_latency", "c2.qti.avc.decoder")
    }

    @Test
    fun `software decoders are never chosen, even when they come first and advertise low latency`() {
        val order = chooseDecoders(listOf(software, hardwareDecoder("OMX.qcom.video.decoder.avc")))

        assertThat(order.map { it.name }).containsExactly("OMX.qcom.video.decoder.avc")
    }

    @Test
    fun `aliases, secure-only decoders and decoders that cannot take the size are skipped`() {
        val alias = hardwareDecoder("alias.avc").copy(alias = true)
        val secure = hardwareDecoder("OMX.qcom.video.decoder.avc.secure").copy(secureOnly = true)
        val small = hardwareDecoder("small.avc").copy(sizeSupported = false)

        assertThat(chooseDecoders(listOf(alias, secure, small))).isEmpty()
    }

    @Test
    fun `among equals the platform's order stays`() {
        val names = listOf("a", "b", "c")

        val order = chooseDecoders(names.map { hardwareDecoder(it) })

        assertThat(order.map { it.name }).isEqualTo(names)
    }

    @Test
    fun `the tuned format asks for low latency, realtime priority, the frame rate and only the vendor keys listed`() {
        val config = checkNotNull(vector("ffmpeg-64x64.avcc").let { AvcConfigParser.parse(it, 0, it.size) })
        val decoder = hardwareDecoder("c2.qti.avc.decoder.low_latency", lowLatency = true)
        val listed = listOf("vendor.qti-ext-dec-picture-order.enable", "vendor.other.key")

        val format = decoderFormat(Tuning.TUNED, decoder, listed, StreamSetup(Display(1280, 720, 60), config, 691_200))

        assertThat(format.lowLatency).isTrue()
        assertThat(format.realtime).isTrue()
        assertThat(format.operatingRate).isEqualTo(60)
        assertThat(format.vendorKeys).containsExactly("vendor.qti-ext-dec-picture-order.enable")
        assertThat(format.csd0.toList()).isEqualTo(config.csd0.toList())
        assertThat(format.mime).isEqualTo("video/avc")
    }

    @Test
    fun `low latency is asked for only where the decoder advertises it`() {
        val config = checkNotNull(vector("ffmpeg-64x64.avcc").let { AvcConfigParser.parse(it, 0, it.size) })

        val format =
            decoderFormat(
                Tuning.TUNED,
                hardwareDecoder("OMX.qcom.video.decoder.avc"),
                emptyList(),
                StreamSetup(Display(1280, 720, 60), config, 1),
            )

        assertThat(format.lowLatency).isFalse()
        assertThat(format.realtime).isTrue()
    }

    @Test
    fun `the plain format carries only CSD and the input size`() {
        val config = checkNotNull(vector("ffmpeg-64x64.avcc").let { AvcConfigParser.parse(it, 0, it.size) })
        val decoder = hardwareDecoder("x", lowLatency = true)

        val format =
            decoderFormat(
                Tuning.PLAIN,
                decoder,
                LOW_LATENCY_VENDOR_KEYS,
                StreamSetup(Display(1280, 720, 60), config, 691_200),
            )

        assertThat(format.lowLatency).isFalse()
        assertThat(format.realtime).isFalse()
        assertThat(format.operatingRate).isEqualTo(0)
        assertThat(format.vendorKeys).isEmpty()
        assertThat(format.maxInputSize).isEqualTo(691_200)
    }

    @Test
    fun `the input size follows the picture, as CarLink sizes it`() {
        // CarLink AndroidMediaSink.kt videoInputSize KDoc: "1280x720 gives 675 KB instead of 8 MB per buffer".
        assertThat(inputSizeFor(1280, 720)).isEqualTo(675 * 1024)
        assertThat(inputSizeFor(64, 64)).isEqualTo(256 * 1024)
        assertThat(inputSizeFor(8192, 8192)).isEqualTo(8 * 1024 * 1024)
    }
}

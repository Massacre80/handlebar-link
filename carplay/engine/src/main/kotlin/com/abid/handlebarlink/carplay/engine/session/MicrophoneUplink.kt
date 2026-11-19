package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneFormat
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneOffer
import com.abid.handlebarlink.carplay.engine.spi.MicrophonePort
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.carplay.protocol.audio.AudioFormat
import com.abid.handlebarlink.carplay.protocol.audio.MicrophonePacketSealer
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * What `/info` offers the microphone this attempt (DiPlay: AirPlayConfig.microphone and microphoneOpus). A port that
 * fails or takes longer than [MICROPHONE_OFFER_TIMEOUT] offers none: CarPlay then works without Siri's microphone.
 */
internal suspend fun offeredMicrophone(
    port: MicrophonePort,
    log: Logger,
): MicrophoneOffer {
    val offer =
        try {
            withTimeoutOrNull(MICROPHONE_OFFER_TIMEOUT) { port.offer() }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // Ours: the attempt ends. Otherwise the port's own timeout.
            log.warn("carplay.audio.microphone_offer_failed", "error" to e.toString())
            null
        } catch (e: CarPlayException) {
            log.warn("carplay.audio.microphone_offer_failed", "error" to e.error)
            null
        }
    return (offer ?: MicrophoneOffer.NONE).also { log.info("carplay.audio.microphone_offer", "offer" to it) }
}

/** The offer is local (a permission and the codec list); a port that takes longer offers none. */
internal const val MICROPHONE_OFFER_TIMEOUT = 2_000L

/**
 * The microphone of one audio stream, as DiPlay derives it from the stream SETUP (MicrophoneConfig,
 * CarPlayMediaEngine.kt:624-663; MicrophonePacketizer.kt:6-71): what to capture, where the iPhone listens, and how far
 * each packet moves the RTP clock.
 */
internal class MicrophoneStream(
    /** What the platform records. */
    val format: MicrophoneFormat,
    /** The iPhone's uplink port: the stream SETUP's `dataPort`. */
    val port: Int,
    /** The RTP timestamp step per packet: the Opus format's clock over 20 ms, or the PCM samples per packet. */
    val rtpSamplesPerPacket: Int,
) {
    companion object {
        private const val MAIN_AUDIO = 100
        private const val MAX_PORT = 65_535
        private const val OPUS_FRAME_MILLIS = 20
        private const val OPUS_CAPTURE_RATE = 48_000
        private const val MIN_FRAME_MILLIS = 5
        private const val MAX_FRAME_MILLIS = 60
        private const val MILLIS_PER_SECOND = 1000
        private const val BYTES_PER_SAMPLE = 2
        private const val OPUS_48K = 0x40000000L
        private const val OPUS_24K = 0x20000000L
        private const val BITRATE_48K = 96_000
        private const val BITRATE_24K = 64_000
        private const val BITRATE_DEFAULT = 48_000

        /** Header, Poly1305 tag and nonce tail (MicrophonePacketizer.kt:93). */
        private const val SEAL_OVERHEAD = 12 + 16 + 8

        /** A 1,500-byte link MTU less the 40-byte IPv6 and 8-byte UDP headers (MicrophonePacketizer.kt:69-70). */
        private const val MAX_UDP_PAYLOAD = 1500 - 40 - 8
        private val PCM_FRAME_MILLIS = listOf(20, 10, 5)

        /**
         * The microphone [stream] asks for, or null (CarPlayMediaEngine.kt:630-635): only with a microphone [offer]ed,
         * only on the main audio stream, only for audio types that record ([wants]) and only with a valid `dataPort`.
         * An AAC stream gets none: `/info` never offers AAC for input.
         */
        fun of(
            stream: AudioStreamSetup,
            offer: MicrophoneOffer,
        ): MicrophoneStream? {
            val port = uplinkPort(stream, offer) ?: return null
            val decode = AudioFormat.fromBits(stream.audioFormat)
            return if (decode.codec == AudioCodec.AAC_LC) null else build(stream, decode, port)
        }

        /** The iPhone's uplink port when [stream] records with [offer], else null. */
        private fun uplinkPort(
            stream: AudioStreamSetup,
            offer: MicrophoneOffer,
        ): Int? {
            val eligible = offer != MicrophoneOffer.NONE && stream.type == MAIN_AUDIO
            return stream.request
                .long("dataPort")
                ?.takeIf { eligible && it in 1..MAX_PORT && wants(stream.audioType, stream.request) }
                ?.toInt()
        }

        private fun build(
            stream: AudioStreamSetup,
            decode: AudioFormat,
            port: Int,
        ): MicrophoneStream {
            val opus = decode.codec == AudioCodec.OPUS
            val framesPerPacket = stream.request.long("framesPerPacket")?.toInt() ?: 0
            val frameMillis =
                when {
                    opus -> {
                        OPUS_FRAME_MILLIS
                    }

                    framesPerPacket > 0 -> {
                        (framesPerPacket * MILLIS_PER_SECOND.toDouble() / decode.sampleRate)
                            .roundToInt()
                            .coerceIn(MIN_FRAME_MILLIS, MAX_FRAME_MILLIS)
                    }

                    else -> {
                        pcmFrameMillis(decode.sampleRate, decode.channels)
                    }
                }
            val bitrate =
                when {
                    !opus -> 0
                    stream.audioFormat and OPUS_48K != 0L -> BITRATE_48K
                    stream.audioFormat and OPUS_24K != 0L -> BITRATE_24K
                    else -> BITRATE_DEFAULT
                }
            val format =
                MicrophoneFormat(
                    audioType = stream.audioType,
                    codec = decode.codec,
                    sampleRate = if (opus) OPUS_CAPTURE_RATE else decode.sampleRate,
                    channels = decode.channels,
                    frameMillis = frameMillis,
                    bitrate = bitrate,
                )
            val step =
                if (opus) {
                    decode.rtpClockRate * OPUS_FRAME_MILLIS / MILLIS_PER_SECOND
                } else {
                    maxOf(1, decode.sampleRate * frameMillis / MILLIS_PER_SECOND)
                }
            return MicrophoneStream(format, port, step)
        }

        /**
         * Calls and Siri always record; app audio ("default", "compatibility") only when the iPhone asks: an `input`
         * flag, or without one the uplink port it sends (CarPlayMediaEngine.kt:684-697).
         */
        fun wants(
            audioType: String,
            request: Plist.Dict,
        ): Boolean =
            when (audioType) {
                "telephony", "speechrecognition" -> {
                    true
                }

                "default", "compatibility" -> {
                    when (val input = request["input"]) {
                        is Plist.Bool -> input.value
                        is Plist.Integer -> input.bits != 0L
                        is Plist.Real -> input.value.toInt() != 0
                        else -> request.long("dataPort")?.let { it in 1..MAX_PORT } == true
                    }
                }

                else -> {
                    false
                }
            }

        /**
         * Milliseconds of PCM per packet when the iPhone names no `framesPerPacket`: the longest of 20, 10 and 5 ms
         * whose sealed packet fits one link MTU (MicrophonePacketizer.kt:56-67).
         */
        fun pcmFrameMillis(
            sampleRate: Int,
            channels: Int,
        ): Int =
            PCM_FRAME_MILLIS.firstOrNull { millis ->
                SEAL_OVERHEAD + sampleRate * millis / MILLIS_PER_SECOND * channels * BYTES_PER_SAMPLE <= MAX_UDP_PAYLOAD
            } ?: PCM_FRAME_MILLIS.last()

        /**
         * Telephony waits for its downlink, whose far-end audio the platform's echo canceller needs; any other
         * stream also starts [SILENT_START_MILLIS] after its SETUP when no packet arrives (MicrophoneStart.kt:8-51).
         */
        fun startsWithoutDownlink(audioType: String): Boolean = audioType != "telephony"

        /** MicrophoneStart.SILENT_START_MILLIS. */
        const val SILENT_START_MILLIS = 500L
    }
}

/**
 * Sends one stream's microphone to the iPhone: what [port] captures, sealed with the stream's input key in DiPlay's
 * layout ([MicrophonePacketSealer], MicrophonePacketizer.kt:95-126; PCM made big-endian first, :128-139), each body
 * one datagram from a socket of its own (MicrophoneUplink.kt:149-153, 559-578) to the control connection's peer
 * (CarPlayMediaEngine.kt:634). A send that fails is counted and skipped; the recorder failing ends only the uplink.
 */
internal class MicrophoneUplink(
    private val context: MediaContext,
    private val port: MicrophonePort,
) {
    suspend fun run(
        stream: AudioStreamSetup,
        microphone: MicrophoneStream,
    ) {
        val format = microphone.format
        val socket = context.network.bindUdp()
        val sealer = MicrophonePacketSealer(stream.inputKey, context.cipherName, payloadType = stream.type)
        val counts = Counts()
        context.log.info(
            "carplay.audio.microphone_start",
            "type" to stream.type,
            "audioType" to stream.audioType,
            "codec" to format.codec,
            "sampleRate" to format.sampleRate,
            "channels" to format.channels,
            "frameMillis" to format.frameMillis,
            "rtpStep" to microphone.rtpSamplesPerPacket,
        )
        try {
            port.capture(format).collect { body ->
                val wire = if (format.codec == AudioCodec.LPCM) MicrophonePacketSealer.toWirePcm(body) else body
                send(socket, sealer.seal(wire, microphone.rtpSamplesPerPacket), microphone.port, counts)
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // Ours (TEARDOWN); otherwise the port's own timeout.
            context.log.warn(
                "carplay.audio.microphone_failed",
                "audioType" to stream.audioType,
                "error" to e.toString(),
            )
        } catch (e: CarPlayException) {
            context.log.warn("carplay.audio.microphone_failed", "audioType" to stream.audioType, "error" to e.error)
        } finally {
            socket.close()
            context.log.info(
                "carplay.audio.microphone_ended",
                "audioType" to stream.audioType,
                "packets" to counts.sent,
                "sendFailures" to counts.failed,
            )
        }
    }

    private class Counts {
        var sent = 0L
        var failed = 0L
    }

    private suspend fun send(
        socket: UdpSocket,
        packet: ByteArray,
        port: Int,
        counts: Counts,
    ) {
        try {
            socket.send(packet, context.peer, port)
            counts.sent++
        } catch (e: CarPlayException) {
            if (counts.failed++ == 0L) context.log.warn("carplay.audio.microphone_send_failed", "error" to e.error)
        }
    }
}

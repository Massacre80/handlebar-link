package com.abid.handlebarlink.carplay.engine.android.video

import com.abid.handlebarlink.carplay.engine.spi.ScreenPort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The engine's [ScreenPort] on the [ScreenDecoder] (docs/PLAN.md C4): the stream SETUP prewarms the decoder, each frame
 * is submitted as it comes (the decoder never blocks the engine's reader), and the decoder's key frame requests go back
 * to the engine as [keyFrameRequests]. The decoder lives as long as the process; between sessions it keeps its surface.
 *
 * Framing: the screen stream reader hands over the `avcC` record as the iPhone sent it and pictures already in Annex B
 * (it replaces each 4-byte NAL length with a start code, DiPlay ScreenStream.kt:172-203). The decoder reads pictures as
 * its configuration says, so an `avcC` with 4-byte lengths goes in as its SPS and PPS behind start codes
 * ([decoderConfig]): the pictures that follow are then read as the Annex B they are, never by guessing whether lengths
 * happen to tile them (an Annex B picture can, docs/LESSONS.md 71).
 *
 * Pictures are decrypted into [PictureBuffers] ([pictureBuffer]), which the decoder gives back once it has copied each
 * into the codec ([ScreenDecoder.FrameRecycler]): no picture-sized allocation per frame.
 */
internal class AndroidScreenPort(
    decoderFactory: (ScreenDecoder.Listener, ScreenDecoder.FrameRecycler) -> ScreenDecoder,
) : ScreenPort {
    /** The picture buffers the reader decrypts into and the decoder gives back. */
    val buffers = PictureBuffers()

    /** Dropped when no screen stream listens (no subscriber); a burst while one listens collapses into one. */
    private val requests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val statsFlow = MutableStateFlow<CarPlayVideoStats?>(null)

    /** The last window of the picture's numbers in this stream; null until one ended. */
    val stats: StateFlow<CarPlayVideoStats?> = statsFlow.asStateFlow()

    /** The decoder, for [com.abid.handlebarlink.carplay.engine.android.CarPlayScreen.show]. */
    val decoder: ScreenDecoder =
        decoderFactory(
            object : ScreenDecoder.Listener {
                // The decoder thread only emits; the engine decides (AGENTS.md, platform callbacks only trySend).
                override fun onKeyFrameNeeded() {
                    requests.tryEmit(Unit)
                }

                override fun onStats(stats: CarPlayVideoStats) {
                    statsFlow.value = stats
                }
            },
            buffers::give,
        )

    override val keyFrameRequests: Flow<Unit> = requests.asSharedFlow()

    override fun configure(
        width: Int,
        height: Int,
        framesPerSecond: Int,
    ) {
        // A new screen stream: the last one's numbers say nothing about this link.
        statsFlow.value = null
        decoder.configure(width, height, framesPerSecond)
    }

    override fun frame(
        isConfig: Boolean,
        senderNanos: Long,
        payload: ByteArray,
        length: Int,
    ) {
        if (isConfig) {
            val config = decoderConfig(if (length == payload.size) payload else payload.copyOf(length))
            decoder.submit(config, 0, config.size, senderNanos / NANOS_PER_MICRO, true)
        } else {
            decoder.submit(payload, 0, length, senderNanos / NANOS_PER_MICRO, false)
        }
    }

    override fun pictureBuffer(size: Int): ByteArray = buffers.take(size)

    companion object {
        private const val NANOS_PER_MICRO = 1_000L

        /** The NAL length the screen stream reader turns into start codes (ScreenStreamReader). */
        private const val READER_NAL_LENGTH = 4

        /**
         * The configuration to hand the decoder for [record]: an `avcC` with 4-byte NAL lengths becomes its SPS and
         * PPS, each behind a 4-byte start code (Annex B, as ffmpeg's `h264_mp4toannexb` writes them). Anything else
         * goes in as it is: pictures behind another length size were left alone by the reader and stay
         * length-prefixed, and a record the parser cannot read is the decoder's to report.
         */
        fun decoderConfig(record: ByteArray): ByteArray {
            val config = AvcConfigParser.parse(record, 0, record.size)
            return if (config?.nalLengthSize == READER_NAL_LENGTH) config.csd0 + config.csd1 else record
        }
    }
}

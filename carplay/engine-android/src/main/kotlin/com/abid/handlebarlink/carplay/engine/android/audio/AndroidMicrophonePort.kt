package com.abid.handlebarlink.carplay.engine.android.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneFormat
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneOffer
import com.abid.handlebarlink.carplay.engine.spi.MicrophonePort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import android.media.AudioFormat as PlatformFormat

/**
 * The [MicrophonePort] on Android. [offer] checks `RECORD_AUDIO` and looks for an Opus encoder (the platform's
 * MediaCodec encoder, `c2.android.opus.encoder` in AOSP's media module) on the I/O thread, once per process
 * (DiPlay OpusEncoderSupport.kt). [capture] records on a thread of its own at audio priority with [MicrophoneCapture];
 * bodies wait in a buffer of [BUFFERED_BODIES] that drops the oldest, so the recorder never waits for the engine.
 */
internal class AndroidMicrophonePort(
    private val audio: AndroidAudioSystem,
) : MicrophonePort {
    private val platform = AndroidMicrophonePlatform(audio)

    private val opus: Boolean by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, 1),
        ) != null
    }

    override suspend fun offer(): MicrophoneOffer =
        withContext(audio.platform.io) {
            when {
                !platform.hasPermission() -> MicrophoneOffer.NONE
                opus -> MicrophoneOffer.PCM_AND_OPUS
                else -> MicrophoneOffer.PCM
            }
        }

    @Suppress("TooGenericExceptionCaught") // Any platform failure ends only this microphone, as a CarPlayException.
    override fun capture(format: MicrophoneFormat): Flow<ByteArray> =
        callbackFlow {
            val capture = MicrophoneCapture(format, platform, audio.calls, audio.platform.log) { trySend(it) }
            val thread =
                Thread({
                    audioPriority()
                    try {
                        capture.run()
                        channel.close()
                    } catch (e: CarPlayException) {
                        channel.close(e)
                    } catch (_: InterruptedException) {
                        channel.close()
                    } catch (e: RuntimeException) {
                        channel.close(CarPlayException(CarPlayError.Unexpected(e), e))
                    }
                }, "carplay-microphone").apply { isDaemon = true }
            thread.start()
            awaitClose {
                capture.stop()
                thread.interrupt()
            }
        }.buffer(BUFFERED_BODIES, BufferOverflow.DROP_OLDEST)

    private companion object {
        const val OPUS_RATE = 48_000

        /** About half a second of 20 ms bodies. */
        const val BUFFERED_BODIES = 25
    }
}

private class AndroidMicrophonePlatform(
    private val audio: AndroidAudioSystem,
) : MicrophonePlatform {
    override fun hasPermission(): Boolean =
        audio.platform.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun minBufferBytes(
        sampleRate: Int,
        channels: Int,
    ): Int = AudioRecord.getMinBufferSize(sampleRate, channelMask(channels), PlatformFormat.ENCODING_PCM_16BIT)

    override fun recorder(
        source: Int,
        format: MicrophoneFormat,
        bufferBytes: Int,
    ): Recorder {
        // Checked here, next to the build, so lint sees it (docs/LESSONS.md 28).
        if (audio.platform.context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw CarPlayException(CarPlayError.PermissionMissing(Manifest.permission.RECORD_AUDIO))
        }
        val record =
            AudioRecord
                .Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    PlatformFormat
                        .Builder()
                        .setEncoding(PlatformFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask(format.channels))
                        .build(),
                ).setBufferSizeInBytes(bufferBytes)
                .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw UnsupportedOperationException("AudioRecord did not initialize (source $source)")
        }
        return object : Recorder {
            override val audioSessionId: Int get() = record.audioSessionId

            override fun start(): Boolean {
                record.startRecording()
                return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = record.read(buffer, offset, length, AudioRecord.READ_BLOCKING)

            override fun stop() = record.stop()

            override fun release() = record.release()
        }
    }

    override fun encoder(bitrate: Int): OpusEncoder = MediaCodecOpusEncoder(bitrate)

    /** AEC and NS on a call's session where the phone has them; one that fails to enable is left out (MU:225-288). */
    override fun voiceEffects(sessionId: Int): AutoCloseable {
        val effects =
            listOfNotNull(
                effect(
                    "aec",
                ) { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null },
                effect("ns") { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null },
            )
        return AutoCloseable { effects.forEach(AudioEffect::release) }
    }

    @Suppress("TooGenericExceptionCaught") // A vendor effect that fails to build is left out; recording goes on.
    private fun effect(
        name: String,
        create: () -> AudioEffect?,
    ): AudioEffect? {
        val effect =
            try {
                create()
            } catch (e: RuntimeException) {
                audio.platform.log.warn("carplay.audio.microphone_effect", "effect" to name, "error" to e.toString())
                null
            } ?: return null
        val enabled = effect.setEnabled(true) == AudioEffect.SUCCESS && effect.enabled
        audio.platform.log.info("carplay.audio.microphone_effect", "effect" to name, "enabled" to enabled)
        if (!enabled) effect.release()
        return effect.takeIf { enabled }
    }

    private fun channelMask(channels: Int): Int =
        if (channels >= 2) PlatformFormat.CHANNEL_IN_STEREO else PlatformFormat.CHANNEL_IN_MONO
}

/** 20 ms frames of 48 kHz mono PCM to raw Opus access units with MediaCodec (DiPlay OpusEncoder.kt:11-142). */
private class MediaCodecOpusEncoder(
    bitrate: Int,
) : OpusEncoder {
    private val codec: MediaCodec =
        try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        } catch (e: IOException) {
            throw IllegalStateException("no Opus encoder", e)
        }
    private val info = MediaCodec.BufferInfo()
    private var presentationMicros = 0L

    init {
        val format =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: IllegalStateException) {
            codec.release()
            throw e
        } catch (e: IllegalArgumentException) {
            codec.release()
            throw e
        }
    }

    override fun encode(pcm: ByteArray): List<ByteArray> {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_MICROS)
        if (index >= 0) {
            val input = codec.getInputBuffer(index)
            if (input == null || pcm.size > input.capacity()) {
                codec.queueInputBuffer(index, 0, 0, presentationMicros, 0)
            } else {
                input.clear()
                input.put(pcm)
                codec.queueInputBuffer(index, 0, pcm.size, presentationMicros, 0)
                presentationMicros += FRAME_MICROS
            }
        }
        val output = ArrayList<ByteArray>()
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (out < 0) return output
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val buffer = checkNotNull(codec.getOutputBuffer(out))
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                output += ByteArray(info.size).also { buffer.get(it) }
            }
            codec.releaseOutputBuffer(out, false)
        }
    }

    override fun release() {
        try {
            codec.stop()
        } finally {
            codec.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val MAX_INPUT_BYTES = 4_096
        const val INPUT_TIMEOUT_MICROS = 10_000L
        const val FRAME_MICROS = 20_000L
    }
}

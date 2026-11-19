package com.abid.handlebarlink.carplay.engine.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import com.abid.handlebarlink.carplay.engine.android.AndroidPlatform
import com.abid.handlebarlink.carplay.engine.spi.AudioPlayer
import com.abid.handlebarlink.carplay.engine.spi.AudioPort
import com.abid.handlebarlink.carplay.engine.spi.AudioStreamInfo
import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.carplay.protocol.audio.AudioDecoderConfig
import com.abid.handlebarlink.carplay.protocol.audio.AudioFormat
import java.io.IOException
import java.nio.ByteBuffer
import android.media.AudioFormat as PlatformFormat

/**
 * What CarPlay's sound and microphone share on this phone: the audio manager (looked up when first used, never while
 * the ports are built, docs/LESSONS.md 60), the one focus request and the call's audio mode.
 */
internal class AndroidAudioSystem(
    val platform: AndroidPlatform,
) {
    val manager: AudioManager by lazy { checkNotNull(platform.context.getSystemService(AudioManager::class.java)) }

    val focus: AudioFocus by lazy { AudioFocus(AndroidFocusSystem(this), platform.log) }

    val calls: CallAudio by lazy { CallAudio(AndroidAudioModeSystem(this), platform.log) }

    val renderers: RendererPlatform by lazy { AndroidRendererPlatform(this) }

    companion object {
        fun attributes(route: AudioRoute): AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(route.usage)
                .setContentType(route.contentType)
                .build()
    }
}

/**
 * The [AudioPort] on Android: each stream gets an [AudioRenderer] on a thread of its own at audio priority (DiPlay
 * AudioStream.kt:92-93: other work must not starve it); `duckAudio` lowers music through [AudioFocus].
 */
internal class AndroidAudioPort(
    private val audio: AndroidAudioSystem,
) : AudioPort {
    override fun open(stream: AudioStreamInfo): AudioPlayer {
        val platform = audio.platform
        val renderer = AudioRenderer(stream, audio.renderers, audio.focus, audio.calls, platform.time, platform.log)
        val thread =
            Thread({
                audioPriority()
                renderer.run()
            }, "carplay-audio-${stream.type}-${stream.audioType}").apply { isDaemon = true }
        renderer.onClose = { thread.interrupt() }
        thread.start()
        return renderer
    }

    override fun duck(ducked: Boolean) {
        audio.focus.setPromptDuck(ducked)
    }
}

/** `THREAD_PRIORITY_AUDIO` for the calling thread; a refusal keeps the default priority. */
internal fun audioPriority() {
    try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
    } catch (_: SecurityException) {
        // Keeps the default priority.
    } catch (_: IllegalArgumentException) {
        // Keeps the default priority.
    }
}

private class AndroidFocusSystem(
    private val audio: AndroidAudioSystem,
) : FocusSystem {
    override fun request(
        gain: Int,
        route: AudioRoute,
        onChange: (Int) -> Unit,
    ): FocusHandle {
        val request =
            AudioFocusRequest
                .Builder(gain)
                .setAudioAttributes(AndroidAudioSystem.attributes(route))
                .setOnAudioFocusChangeListener({ change -> onChange(change) }, audio.platform.handler)
                .build()
        val result = audio.manager.requestAudioFocus(request)
        return object : FocusHandle {
            override val granted: Boolean = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            override fun abandon() {
                audio.manager.abandonAudioFocusRequest(request)
            }
        }
    }
}

private class AndroidAudioModeSystem(
    private val audio: AndroidAudioSystem,
) : AudioModeSystem {
    override var mode: Int
        get() = audio.manager.mode
        set(value) {
            audio.manager.mode = value
        }

    override fun loudspeaker(): Boolean {
        val speaker =
            audio.manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return speaker != null && audio.manager.setCommunicationDevice(speaker)
    }

    override fun clearCommunicationDevice() {
        audio.manager.clearCommunicationDevice()
    }
}

/** AudioTrack and MediaCodec for [AudioRenderer]. */
private class AndroidRendererPlatform(
    private val audio: AndroidAudioSystem,
) : RendererPlatform {
    override val nativeRate: Int by lazy {
        audio.manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
    }

    override val burstFrames: Int by lazy {
        audio.manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
    }

    override fun minBufferBytes(
        sampleRate: Int,
        channels: Int,
    ): Int = AudioTrack.getMinBufferSize(sampleRate, channelMask(channels), PlatformFormat.ENCODING_PCM_16BIT)

    override fun decoder(format: AudioFormat): PcmDecoder = MediaCodecPcmDecoder(format)

    override fun track(
        route: AudioRoute,
        format: AudioFormat,
        plan: PlaybackPlan,
        performanceMode: Int,
    ): PcmTrack {
        val channels = format.channels.coerceIn(1, 2)
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(AndroidAudioSystem.attributes(route))
                .setAudioFormat(
                    PlatformFormat
                        .Builder()
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask(channels))
                        .setEncoding(PlatformFormat.ENCODING_PCM_16BIT)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.capacityFrames * plan.frameBytes)
                .setPerformanceMode(performanceMode)
                .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack did not initialize at ${format.sampleRate} Hz")
        }
        val listener =
            if (route.channel == AudioChannel.PHONE) {
                AudioRouting
                    .OnRoutingChangedListener { routing ->
                        if (routing.routedDevice?.type ==
                            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        ) {
                            audio.calls.routedToEarpiece()
                        }
                    }.also { track.addOnRoutingChangedListener(it, audio.platform.handler) }
            } else {
                null
            }
        return AndroidPcmTrack(track, listener)
    }

    private fun channelMask(channels: Int): Int =
        if (channels >= 2) PlatformFormat.CHANNEL_OUT_STEREO else PlatformFormat.CHANNEL_OUT_MONO
}

private class AndroidPcmTrack(
    private val track: AudioTrack,
    private val routing: AudioRouting.OnRoutingChangedListener?,
) : PcmTrack {
    override val capacityFrames: Int get() = track.bufferCapacityInFrames
    override val underrunCount: Int get() = track.underrunCount
    override val playbackHead: Int get() = track.playbackHeadPosition
    override val performanceMode: Int get() = track.performanceMode
    override val onEarpiece: Boolean get() = track.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    override fun write(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int = track.write(data, offset, length, AudioTrack.WRITE_BLOCKING)

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun setBufferFrames(frames: Int): Int = track.setBufferSizeInFrames(frames)

    override fun setVolume(volume: Float) {
        track.setVolume(volume)
    }

    override fun release() {
        routing?.let(track::removeOnRoutingChangedListener)
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
            track.pause()
            track.flush()
        }
        track.release()
    }
}

/**
 * MediaCodec in synchronous mode on the renderer's thread (DiPlay AndroidMediaSink.kt:2062-2104, 2423-2529): AAC-LC as
 * ADTS with its AudioSpecificConfig, Opus with OpusHead and its delays, both at the stream's rate and channels.
 */
private class MediaCodecPcmDecoder(
    format: AudioFormat,
) : PcmDecoder {
    private val mime =
        if (format.codec ==
            AudioCodec.AAC_LC
        ) {
            MediaFormat.MIMETYPE_AUDIO_AAC
        } else {
            MediaFormat.MIMETYPE_AUDIO_OPUS
        }
    private val codec: MediaCodec =
        try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: IOException) {
            throw IllegalStateException("no $mime decoder", e)
        }
    private val info = MediaCodec.BufferInfo()
    private var pcm = ByteArray(PCM_BUFFER_BYTES)

    override val name: String = codec.name

    init {
        val mediaFormat =
            MediaFormat.createAudioFormat(mime, format.sampleRate, format.channels).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
                if (format.codec == AudioCodec.AAC_LC) {
                    setInteger(MediaFormat.KEY_IS_ADTS, 1)
                    setByteBuffer(
                        CSD_0,
                        ByteBuffer.wrap(AudioDecoderConfig.aacSpecificConfig(format.sampleRate, format.channels)),
                    )
                } else {
                    val (delay, preRoll) = AudioDecoderConfig.opusDelays()
                    setByteBuffer(
                        CSD_0,
                        ByteBuffer.wrap(AudioDecoderConfig.opusHead(format.sampleRate, format.channels)),
                    )
                    setByteBuffer("csd-1", ByteBuffer.wrap(delay))
                    setByteBuffer("csd-2", ByteBuffer.wrap(preRoll))
                }
            }
        try {
            codec.configure(mediaFormat, null, null, 0)
            codec.start()
        } catch (e: IllegalStateException) {
            codec.release()
            throw e
        } catch (e: IllegalArgumentException) {
            codec.release()
            throw e
        }
    }

    override fun queue(
        unit: ByteArray,
        presentationMicros: Long,
    ): Boolean {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_MICROS)
        if (index < 0) return false
        val input = checkNotNull(codec.getInputBuffer(index))
        input.clear()
        // A unit larger than the buffer is dropped, its buffer handed back empty (AndroidMediaSink.kt:2451-2465).
        val fits = unit.size <= input.remaining()
        if (fits) input.put(unit)
        codec.queueInputBuffer(index, 0, if (fits) unit.size else 0, if (fits) presentationMicros else 0, 0)
        return true
    }

    override fun drain(output: (ByteArray, Int, Int) -> Unit) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                val size = copyOut(index)
                if (size > 0) output(pcm, 0, size)
            } else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return
            }
        }
    }

    /** Copies output buffer [index] into [pcm] and hands it back; the bytes copied. */
    private fun copyOut(index: Int): Int {
        val size = info.size
        try {
            val buffer = codec.getOutputBuffer(index)
            if (buffer == null || size <= 0) return 0
            if (size > pcm.size) pcm = ByteArray(size)
            buffer.position(info.offset)
            buffer.limit(info.offset + size)
            buffer.get(pcm, 0, size)
            return size
        } finally {
            codec.releaseOutputBuffer(index, false)
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
        const val CSD_0 = "csd-0"
        const val MAX_INPUT_BYTES = 64 * 1024
        const val PCM_BUFFER_BYTES = 64 * 1024
        const val INPUT_TIMEOUT_MICROS = 10_000L
    }
}

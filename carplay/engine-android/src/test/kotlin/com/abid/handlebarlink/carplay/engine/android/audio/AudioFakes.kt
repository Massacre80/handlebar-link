package com.abid.handlebarlink.carplay.engine.android.audio

import android.media.AudioDeviceInfo
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneFormat
import com.abid.handlebarlink.carplay.protocol.audio.AudioFormat
import com.abid.handlebarlink.core.common.time.TimeProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A clock the test moves by hand. */
internal class ManualTime : TimeProvider {
    var now = 0L
    override val clock: Clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC)

    override fun elapsedMillis(): Long = now
}

/** A track that keeps what was written and plays only when the test says ([playAll]). */
internal class FakeTrack(
    override val capacityFrames: Int,
    private val frameBytes: Int,
    override val performanceMode: Int,
) : PcmTrack {
    val written = mutableListOf<ByteArray>()
    val calls = mutableListOf<String>()
    var bufferFrames = 0
    var volumes = mutableListOf<Float>()
    override var underrunCount = 0
    override var playbackHead = 0
    override var onEarpiece = false
    var playing = false
    var released = false

    /** Bytes of every write, joined. */
    val bytes: ByteArray get() = written.fold(ByteArray(0)) { all, next -> all + next }

    override fun write(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        written += data.copyOfRange(offset, offset + length)
        return length
    }

    override fun play() {
        playing = true
        calls += "play"
    }

    override fun pause() {
        playing = false
        calls += "pause"
    }

    override fun setBufferFrames(frames: Int): Int {
        bufferFrames = frames
        calls += "buffer $frames"
        return frames
    }

    override fun setVolume(volume: Float) {
        volumes += volume
    }

    override fun release() {
        released = true
    }

    /** The device plays everything written so far. */
    fun playAll() {
        playbackHead = bytes.size / frameBytes
    }
}

/** A decoder that turns each unit into [output] PCM, or refuses input while [busy] is above 0. */
internal class FakeDecoder : PcmDecoder {
    override val name: String = "fake.decoder"
    val queued = mutableListOf<Pair<String, Long>>()
    var busy = 0
    var drains = 0
    private val ready = ArrayDeque<ByteArray>()
    var released = false

    /** What each unit decodes to. */
    var output: (ByteArray) -> ByteArray = { ByteArray(4) { 0x10 } }

    override fun queue(
        unit: ByteArray,
        presentationMicros: Long,
    ): Boolean {
        if (busy > 0) {
            busy--
            return false
        }
        queued += unit.joinToString("") { "%02x".format(it) } to presentationMicros
        ready += output(unit)
        return true
    }

    override fun drain(output: (ByteArray, Int, Int) -> Unit) {
        drains++
        while (ready.isNotEmpty()) ready.removeFirst().let { output(it, 0, it.size) }
    }

    override fun release() {
        released = true
    }
}

internal class FakeRendererPlatform(
    override val nativeRate: Int = 48_000,
    override val burstFrames: Int = 192,
    private val minBuffer: Int = 3_840,
) : RendererPlatform {
    val decoder = FakeDecoder()
    var track: FakeTrack? = null
    var plan: PlaybackPlan? = null
    var requestedMode: Int? = null
    var preferred: CommunicationDevice? = null

    override fun minBufferBytes(
        sampleRate: Int,
        channels: Int,
    ): Int = minBuffer

    var failDecoder = false

    override fun decoder(format: AudioFormat): PcmDecoder {
        check(!failDecoder) { "no decoder" }
        return decoder
    }

    override fun track(
        route: AudioRoute,
        format: AudioFormat,
        plan: PlaybackPlan,
        performanceMode: Int,
        preferred: CommunicationDevice?,
    ): PcmTrack {
        this.preferred = preferred
        this.plan = plan
        requestedMode = performanceMode
        return FakeTrack(plan.capacityFrames, plan.frameBytes, performanceMode).also { track = it }
    }
}

/** Grants every focus request and records requests, abandons and how to send changes. */
internal class FakeFocusSystem : FocusSystem {
    val requests = mutableListOf<Pair<Int, AudioChannel>>()
    val abandoned = mutableListOf<AudioChannel>()
    var granted = true
    var changes: (Int) -> Unit = {}

    override fun request(
        gain: Int,
        route: AudioRoute,
        onChange: (Int) -> Unit,
    ): FocusHandle {
        requests += gain to route.channel
        changes = onChange
        val wasGranted = granted
        return object : FocusHandle {
            override val granted: Boolean = wasGranted

            override fun abandon() {
                abandoned += route.channel
            }
        }
    }
}

internal class FakeAudioModes : AudioModeSystem {
    val modes = mutableListOf<Int>()
    override var mode: Int = 0
        set(value) {
            field = value
            modes += value
        }
    var speakerGranted = true
    var speakers = 0
    var cleared = 0

    /** What `getAvailableCommunicationDevices` lists now. */
    var devices: List<CommunicationDevice> = listOf(EARPIECE, SPEAKER)
    var deviceGranted = true

    /** Each `setCommunicationDevice`, in order. */
    val routed = mutableListOf<CommunicationDevice>()

    override fun loudspeaker(): Boolean {
        speakers++
        return speakerGranted
    }

    override fun communicationDevices(): List<CommunicationDevice> = devices

    override fun setCommunicationDevice(device: CommunicationDevice): Boolean {
        routed += device
        return deviceGranted
    }

    override fun clearCommunicationDevice() {
        cleared++
    }
}

/** A recorder that returns the test's [reads] in turn (a negative entry is an error), then blocks until stopped. */
internal class FakeRecorder(
    private val reads: ArrayDeque<Any>,
    private val startResults: ArrayDeque<Boolean> = ArrayDeque(),
) : Recorder {
    override val audioSessionId: Int = 42
    var starts = 0
    var stops = 0
    var released = false
    var onEmpty: () -> Unit = {}

    override fun start(): Boolean {
        starts++
        return startResults.removeFirstOrNull() ?: true
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        when (val next = reads.removeFirstOrNull()) {
            null -> {
                onEmpty()
                0
            }

            is Int -> {
                next
            }

            else -> {
                val bytes = next as ByteArray
                check(bytes.size <= length) { "a recorder never returns more than asked for" }
                bytes.copyInto(buffer, offset)
                bytes.size
            }
        }

    override fun stop() {
        stops++
    }

    override fun release() {
        released = true
    }
}

internal class FakeMicrophonePlatform(
    private val recorder: FakeRecorder,
) : MicrophonePlatform {
    var permission = true
    val sources = mutableListOf<Int>()
    var refuse: Set<Int> = emptySet()
    var bufferBytes = 0
    var effectsOpened = 0
    var effectsClosed = 0
    var encoderBitrate: Int? = null
    var preferred: CommunicationDevice? = null
    var encoderReleased = false

    /** What the encoder makes of each frame. */
    var encode: (ByteArray) -> List<ByteArray> = { listOf(byteArrayOf(0xF8.toByte(), it.size.toByte())) }

    override fun hasPermission(): Boolean = permission

    override fun minBufferBytes(
        sampleRate: Int,
        channels: Int,
    ): Int = 1_280

    override fun recorder(
        source: Int,
        format: MicrophoneFormat,
        bufferBytes: Int,
        preferred: CommunicationDevice?,
    ): Recorder {
        this.preferred = preferred
        sources += source
        if (source in refuse) throw UnsupportedOperationException("source $source refused")
        this.bufferBytes = bufferBytes
        return recorder
    }

    override fun encoder(bitrate: Int): OpusEncoder {
        encoderBitrate = bitrate
        return object : OpusEncoder {
            override fun encode(pcm: ByteArray): List<ByteArray> = this@FakeMicrophonePlatform.encode(pcm)

            override fun release() {
                encoderReleased = true
            }
        }
    }

    override fun voiceEffects(sessionId: Int): AutoCloseable {
        effectsOpened++
        return AutoCloseable { effectsClosed++ }
    }
}

/** The phone's own communication devices and two helmets (types from AudioDeviceInfo: 1 earpiece, 2 speaker). */
internal val EARPIECE = CommunicationDevice(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "", "Poco F1")
internal val SPEAKER = CommunicationDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Poco F1")
internal val INTERCOM = CommunicationDevice(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB:CC:DD:EE:01", "EJEAS V6 Pro+")
internal val OTHER_HEADSET = CommunicationDevice(8, AudioDeviceInfo.TYPE_BLE_HEADSET, "AA:BB:CC:DD:EE:02", "Buds")

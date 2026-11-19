package com.abid.handlebarlink.carplay.engine.spi

import com.abid.handlebarlink.carplay.protocol.audio.AudioCodec
import com.abid.handlebarlink.carplay.protocol.audio.AudioFormat
import com.abid.handlebarlink.carplay.protocol.iap2.WifiNetwork
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.AirPlayIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.PairingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// The engine's seams to the platform. `:carplay:engine-android` implements them (RFCOMM, Wi-Fi Direct,
// sockets, mDNS, files); tests use fakes. Every failure is a CarPlayException with a CarPlayError.
// Blocking work happens inside the implementations (their own threads); the engine only suspends.
// Every suspending call must end promptly when its coroutine is cancelled, closing what it was blocked on
// (a blocking socket read, accept or connect does not react to interruption; closing the socket from another
// thread aborts it, Android "Connect Bluetooth devices"): the attempt's teardown waits for its children before
// it closes the radio and the sockets, so a call that ignores cancellation stalls the teardown.

/** A connected byte stream (RFCOMM socket or TCP connection). */
public interface ByteStream {
    /**
     * Reads into [buffer]; returns the count, or -1 when the peer closed the stream or it was closed (Android 17
     * RFCOMM sockets also return -1 when closed, behaviour change for apps targeting API 37). Cancelling a read
     * closes the stream.
     */
    public suspend fun read(buffer: ByteArray): Int

    /** Writes all of [bytes]. */
    public suspend fun write(bytes: ByteArray)

    /** Closes the stream; idempotent, never throws. Unblocks a pending [read]. */
    public fun close()
}

/** Bluetooth: the iAP2 bootstrap link to the paired iPhone. */
public interface BluetoothPort {
    /**
     * Dials the paired iPhone's iAP2 RFCOMM service, UUID 00000000-deca-fade-deca-deafdecacafe, with a
     * secure (authenticated) socket. We are the client (DiPlay CarPlayController.kt:1465, spec A §1).
     *
     * Cancellation aborts the connect by closing the socket from another thread (Android "Connect Bluetooth
     * devices": `close()` aborts a blocked call), so no socket stays open after a cancelled dial.
     */
    public suspend fun dialIap2(): ByteStream

    /** This phone's Bluetooth address, 6 bytes, sent in identification. */
    public fun localAddress(): ByteArray

    /**
     * Emits each time Bluetooth turns on (`ACTION_STATE_CHANGED` to `STATE_ON`). A retry that waits only because
     * Bluetooth was off runs at once (DiPlay PR #914; docs/research/carplay-spec/E-connection.md #5).
     */
    public val radioOn: Flow<Unit>
}

/** A Wi-Fi Direct group with this phone as owner. */
public data class WifiGroup(
    /** What the iPhone joins. */
    val network: WifiNetwork,
    /**
     * Our addresses on the group interface, in any order; IPv6 link-local ones carry their scope
     * ("fe80::1%p2p-wlan0-0"). Never empty. The engine puts IPv4 first (E-connection.md #7).
     */
    val addresses: List<String>,
    /** Our AirPlay receiver id, lower-case "aa:bb:…" (the group interface's MAC where Android reveals it). */
    val interfaceMac: String,
) {
    init {
        require(addresses.isNotEmpty()) { "A Wi-Fi Direct group needs at least one address" }
    }
}

/** Who is in the group now, from the platform's group reports. */
public data class GroupClients(
    /** Stations joined to the group. */
    val joined: Int,
    /** Of those, how many hold a DHCP lease (Android 15+ reports it; always 0 before). */
    val leased: Int,
)

/** Wi-Fi Direct. */
public interface WifiDirectPort {
    /** Creates (or reuses) the group with a stable name, passphrase and channel. */
    public suspend fun createGroup(): WifiGroup

    /**
     * The group's clients while collected: the current snapshot first, then each change. Throws a
     * CarPlayException when the group is lost (the platform dropped our Wi-Fi Direct channel).
     */
    public fun clients(): Flow<GroupClients>

    /** The iPhone reached a verified session on the current group: remember its channel and try it first. */
    public fun groupWorked()

    /** Removes the group; idempotent, never throws. */
    public suspend fun removeGroup()
}

/** A bound TCP server. */
public interface TcpListener {
    /** The bound port, the same on every address. */
    public val port: Int

    /** Waits for the next connection on any of the bound addresses. */
    public suspend fun accept(): TcpConnection

    /** Stops listening; idempotent, never throws. */
    public fun close()
}

/** An accepted TCP connection. */
public interface TcpConnection : ByteStream {
    /** The peer's address, for logs and connection limits. */
    public val remoteAddress: String
}

/** One received UDP datagram. */
public class Datagram(
    /** The payload, exactly as long as the datagram. */
    public val bytes: ByteArray,
    /** The sender's address, in the same form as [TcpConnection.remoteAddress] (IPv6 link-local with its scope). */
    public val address: String,
    /** The sender's port. */
    public val port: Int,
)

/** A bound UDP socket (the AirPlay timing, keep-alive and audio ports). */
public interface UdpSocket {
    /** The bound port. */
    public val port: Int

    /**
     * Waits for the next datagram (up to 65 535 bytes); null once the socket is closed. Cancelling a receive
     * closes the socket (a blocked receive only ends when its socket is closed).
     */
    public suspend fun receive(): Datagram?

    /** Sends [bytes] as one datagram to [address] (a numeric literal, IPv6 with its scope) and [port]. */
    public suspend fun send(
        bytes: ByteArray,
        address: String,
        port: Int,
    )

    /** Closes the socket; idempotent, never throws. Unblocks a pending [receive]. */
    public fun close()
}

/** Sockets. */
public interface NetworkPort {
    /**
     * Listens on every one of [addresses] with one port, preferring [preferredPort] and then the next ten ports
     * (DiPlay AirPlayPortSelector.kt:17-64, `bindAll`). [preferredPort] 0 asks for any free port, the same on
     * every address: the AirPlay session's event, screen and data listeners (DiPlay binds them to port 0,
     * AirPlaySession.kt:885, ScreenStream.kt:49-51). Connections get TCP_NODELAY and keep-alive.
     */
    public suspend fun listen(
        addresses: List<String>,
        preferredPort: Int,
    ): TcpListener

    /**
     * Binds a UDP socket to a free port on every interface (`[::]:0`, dual stack), as DiPlay binds the timing,
     * keep-alive and audio sockets (NtpClock.kt:41-43, AirPlaySession.kt:861-864, AudioStream.kt:192-196). The
     * engine checks senders itself.
     */
    public suspend fun bindUdp(): UdpSocket
}

/** A Bonjour service to publish. */
public data class BonjourService(
    /** Service type, e.g. "_airplay._tcp". */
    val type: String,
    /** Instance name. */
    val name: String,
    /** Address to publish on (the group interface). */
    val address: String,
    /** TCP port. */
    val port: Int,
    /** TXT record, in order. */
    val txt: List<Pair<String, String>>,
)

/** mDNS on the group interface. */
public interface BonjourPort {
    /**
     * Publishes [service] until [unpublish]. Never on the connection's critical path: the engine runs it beside
     * the bootstrap, and a failure is only logged (E-connection.md #4).
     */
    public suspend fun publish(service: BonjourService)

    /** Removes everything published; idempotent, never throws. */
    public suspend fun unpublish()
}

/** What the receiver proves itself with. */
public class CarPlayCredentials(
    /** The MFi accessory identity (iAP2 authentication and AirPlay auth-setup). */
    public val mfi: MfiIdentity,
    /** The long-term AirPlay identity. */
    public val airPlay: AirPlayIdentity,
    /** iPhones that completed pair-setup; persisted by the app. */
    public val pairings: PairingStore,
)

/** Loads the credentials (files, stored identity). */
public fun interface CredentialsPort {
    /** Loads; throws CarPlayException(IdentityUnavailable) when the MFi files are missing or broken. */
    public suspend fun load(): CarPlayCredentials
}

/**
 * The screen CarPlay is shown on: the platform's video decoder (docs/PLAN.md C4). Only the main screen (stream type
 * 110) comes here; `/info` declares no other display. Every call comes from the engine's dispatcher, in the order the
 * iPhone sent the stream, and must return at once: it never blocks or waits for the decoder (the screen stream's
 * reader, and with it the iPhone's TCP sends, would stall). The port outlives sessions: one decoder for the process.
 */
public interface ScreenPort {
    /**
     * A main screen stream was set up (stream SETUP) for a [width] x [height] picture at up to [framesPerSecond], the
     * size `/info` reported: prepare the decoder now, before the first frame arrives (on the Poco a cold decoder start
     * held the first second of pictures, docs/LESSONS.md 77).
     */
    public fun configure(
        width: Int,
        height: Int,
        framesPerSecond: Int,
    )

    /**
     * One message of the main screen stream, decrypted, exactly as the screen stream reader returns it
     * (`ScreenStreamReader`, DiPlay ScreenStream.kt:117-124, 172-203):
     * - [isConfig] true: [payload] is the H.264 decoder configuration record (`avcC`, ISO/IEC 14496-15 §5.3.3.1) as
     *   the iPhone sent it;
     * - [isConfig] false: [payload] is one access unit in Annex B form (each 4-byte NAL length replaced by a start
     *   code), or the decrypted bytes untouched when its lengths do not tile it.
     *
     * [senderNanos] is the iPhone's frame time from the 128-byte header. [payload] now belongs to the port; the
     * engine never touches it again.
     */
    public fun frame(
        isConfig: Boolean,
        senderNanos: Long,
        payload: ByteArray,
    )

    /**
     * Each emission asks the iPhone for a key frame (`forceKeyFrame` on the event channel, DiPlay
     * CarPlayMediaEngine.kt:146-157), while a main screen stream runs; emissions with no stream running are dropped.
     * The decoder asks after it starts, after an error, after dropped pictures and when its surface comes back.
     */
    public val keyFrameRequests: Flow<Unit>

    /** Ports without a screen. */
    public companion object {
        /** No display: every frame is dropped and no key frame is asked for (a platform that shows no picture). */
        public val NONE: ScreenPort =
            object : ScreenPort {
                override fun configure(
                    width: Int,
                    height: Int,
                    framesPerSecond: Int,
                ) {}

                override fun frame(
                    isConfig: Boolean,
                    senderNanos: Long,
                    payload: ByteArray,
                ) {}

                override val keyFrameRequests: Flow<Unit> = emptyFlow()
            }
    }
}

/**
 * One audio stream the iPhone set up (type 100, 101 or 102), as DiPlay hands it to its renderer: the stream's identity
 * and decode format (MediaSink.onAudioStarted, CarPlayMediaEngine.kt:177-229; AudioStream.kt:221-242).
 */
public data class AudioStreamInfo(
    /** 100 main, 101 alternate, 102 main high audio. */
    val type: Int,
    /** The SETUP's `audioType`, lower-cased, "default" when absent: media, default, alert, telephony, … */
    val audioType: String,
    /** What the payloads are: codec, decode rate, channels and the RTP clock ([AudioFormat.fromBits]). */
    val format: AudioFormat,
    /** The SETUP's `audioLatencyMs`, 0 when absent. */
    val latencyMillis: Int,
)

/**
 * Where CarPlay's sound plays: the platform's decoders and audio output (docs/PLAN.md C5). Every call comes from the
 * engine's dispatcher and must return at once: decoding, the output device and audio focus live on the port's own
 * threads, and a failing device stops only its own sound, never the session. The port outlives sessions.
 */
public interface AudioPort {
    /**
     * The first packet of [stream] arrived (DiPlay starts its renderer then, CarPlayMediaEngine.kt:224-228): a player
     * for it. The engine closes it at the stream's TEARDOWN, a replacing SETUP or the session's end.
     */
    public fun open(stream: AudioStreamInfo): AudioPlayer

    /**
     * The iPhone asked to lower other sound for a navigation prompt ([ducked] true, `duckAudio`) or to restore it
     * (`unduckAudio`; AirPlaySession.kt:810-811). A session that ends while ducked is restored by the engine
     * (CarPlayMediaEngine.kt:551-553).
     */
    public fun duck(ducked: Boolean)

    /** Ports without sound. */
    public companion object {
        /** Plays nothing: every packet is dropped. */
        public val NONE: AudioPort =
            object : AudioPort {
                override fun open(stream: AudioStreamInfo): AudioPlayer =
                    object : AudioPlayer {
                        override fun packet(
                            sequence: Int,
                            timestamp: Long,
                            payload: ByteArray,
                        ) {}

                        override fun close() {}
                    }

                override fun duck(ducked: Boolean) {}
            }
    }
}

/** One stream's playback. Calls come from the engine's dispatcher and never block. */
public interface AudioPlayer {
    /**
     * One decrypted RTP packet, in arrival order (the player puts reordered packets back in sequence, as DiPlay's
     * RtpReorderQueue): its 16-bit [sequence], its 32-bit [timestamp] on the stream's RTP clock (0..2^32 − 1), and its
     * [payload] as on the wire: an AAC-LC access unit, an Opus packet, or big-endian 16-bit PCM
     * (MicrophonePacketizer.kt:128). [payload] now belongs to the player.
     */
    public fun packet(
        sequence: Int,
        timestamp: Long,
        payload: ByteArray,
    )

    /** Stops the sound and releases everything; idempotent, returns at once. */
    public fun close()
}

/** What the platform's microphone can send: `/info`'s `audioInputFormats` (AirPlayInfoPlist.kt:155-162). */
public enum class MicrophoneOffer {
    /** No microphone (no permission or no recorder): `/info` offers none. */
    NONE,

    /** 16-bit PCM only. */
    PCM,

    /** PCM and Opus (DiPlay offers Opus whenever an encoder works; wireless iPhones use it, #718). */
    PCM_AND_OPUS,
}

/**
 * How to capture one microphone stream, from the stream SETUP (DiPlay MicrophoneConfig, CarPlayMediaEngine.kt:624-663;
 * MicrophonePacketizer.kt:6-41).
 */
public data class MicrophoneFormat(
    /** The stream's audio type: telephony (a call), speechrecognition (Siri), default or compatibility. */
    val audioType: String,
    /** [AudioCodec.LPCM] or [AudioCodec.OPUS]. */
    val codec: AudioCodec,
    /** The capture rate: the PCM rate, or 48 kHz for Opus. */
    val sampleRate: Int,
    /** 1 or 2. */
    val channels: Int,
    /** Milliseconds of sound per packet: 20 for Opus, else what the iPhone asked for or what fits one MTU. */
    val frameMillis: Int,
    /** The Opus bitrate (96, 64 or 48 kbit/s by the format's rate), 0 for PCM. */
    val bitrate: Int,
)

/**
 * The microphone for Siri and calls (docs/PLAN.md C5). The engine seals and sends what it captures to the iPhone.
 */
public interface MicrophonePort {
    /**
     * What `/info` may offer, asked once per wireless attempt before `/info` is served; it may look at the platform's
     * permissions and codecs (on the port's own thread). [MicrophoneOffer.NONE] when the microphone cannot be used.
     */
    public suspend fun offer(): MicrophoneOffer

    /**
     * Records while collected: each emission is one packet body, in order: [MicrophoneFormat.frameMillis] of
     * little-endian 16-bit PCM ([MicrophoneFormat.sampleRate] x [MicrophoneFormat.channels]), or one Opus access unit
     * of 20 ms. Collecting starts the recorder and cancelling stops and releases it; recording runs on the port's own
     * thread, so a slow collector loses old bodies, never blocks the recorder. A recorder that cannot start or dies
     * throws a [com.abid.handlebarlink.carplay.engine.CarPlayException]; only that stream's microphone ends.
     */
    public fun capture(format: MicrophoneFormat): Flow<ByteArray>

    /** Ports without a microphone. */
    public companion object {
        /** No microphone: offers none and records nothing. */
        public val NONE: MicrophonePort =
            object : MicrophonePort {
                override suspend fun offer(): MicrophoneOffer = MicrophoneOffer.NONE

                override fun capture(format: MicrophoneFormat): Flow<ByteArray> = emptyFlow()
            }
    }
}

/** The platform the receiver runs on. */
public class CarPlayPorts(
    /** Bluetooth. */
    public val bluetooth: BluetoothPort,
    /** Wi-Fi Direct. */
    public val wifiDirect: WifiDirectPort,
    /** Sockets. */
    public val network: NetworkPort,
    /** mDNS. */
    public val bonjour: BonjourPort,
    /** Identities. */
    public val credentials: CredentialsPort,
    /** Where the iPhone's picture goes; [ScreenPort.NONE] drops it. */
    public val screen: ScreenPort = ScreenPort.NONE,
    /** Where CarPlay's sound plays; [AudioPort.NONE] drops it. */
    public val audio: AudioPort = AudioPort.NONE,
    /** Siri's and calls' microphone; [MicrophonePort.NONE] offers none. */
    public val microphone: MicrophonePort = MicrophonePort.NONE,
    /**
     * The platform's name for ChaCha20-Poly1305 (ChaCha20Poly1305.Transformation.ANDROID on the phone, so
     * every frame is decrypted natively by BoringSSL).
     */
    public val cipherName: String,
)

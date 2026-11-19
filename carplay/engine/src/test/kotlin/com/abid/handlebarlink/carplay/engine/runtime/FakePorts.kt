package com.abid.handlebarlink.carplay.engine.runtime

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.AudioPlayer
import com.abid.handlebarlink.carplay.engine.spi.AudioPort
import com.abid.handlebarlink.carplay.engine.spi.AudioStreamInfo
import com.abid.handlebarlink.carplay.engine.spi.BluetoothPort
import com.abid.handlebarlink.carplay.engine.spi.BonjourPort
import com.abid.handlebarlink.carplay.engine.spi.BonjourService
import com.abid.handlebarlink.carplay.engine.spi.ByteStream
import com.abid.handlebarlink.carplay.engine.spi.CarPlayCredentials
import com.abid.handlebarlink.carplay.engine.spi.CarPlayPorts
import com.abid.handlebarlink.carplay.engine.spi.Datagram
import com.abid.handlebarlink.carplay.engine.spi.GroupClients
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneFormat
import com.abid.handlebarlink.carplay.engine.spi.MicrophoneOffer
import com.abid.handlebarlink.carplay.engine.spi.MicrophonePort
import com.abid.handlebarlink.carplay.engine.spi.NetworkPort
import com.abid.handlebarlink.carplay.engine.spi.ScreenPort
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.TcpListener
import com.abid.handlebarlink.carplay.engine.spi.UdpSocket
import com.abid.handlebarlink.carplay.engine.spi.WifiDirectPort
import com.abid.handlebarlink.carplay.engine.spi.WifiGroup
import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.iap2.WifiNetwork
import com.abid.handlebarlink.carplay.protocol.iap2.WifiSecurity
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.AirPlayIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.InMemoryPairingStore
import com.abid.handlebarlink.carplay.protocol.pairing.PairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

/** Two connected in-memory stream ends. Closing either end ends both: reads return -1. */
internal class Pipe(
    remoteAddress: String = "192.168.49.2",
) {
    private val toA = Channel<ByteArray>(Channel.UNLIMITED)
    private val toB = Channel<ByteArray>(Channel.UNLIMITED)

    /** The receiver's end. */
    val ours = End(toA, toB, remoteAddress)

    /** The iPhone's end. */
    val phone = End(toB, toA, "192.168.49.1")

    class End(
        private val incoming: Channel<ByteArray>,
        private val outgoing: Channel<ByteArray>,
        override val remoteAddress: String,
    ) : TcpConnection {
        private var pending = ByteArray(0)
        var closed = false
            private set

        override suspend fun read(buffer: ByteArray): Int {
            if (pending.isEmpty()) pending = incoming.receiveCatching().getOrNull() ?: return -1
            val count = minOf(buffer.size, pending.size)
            pending.copyInto(buffer, 0, 0, count)
            pending = pending.copyOfRange(count, pending.size)
            return count
        }

        override suspend fun write(bytes: ByteArray) {
            if (closed) throw CarPlayException(CarPlayError.NetworkFailed("closed"))
            outgoing.trySend(bytes.copyOf())
        }

        override fun close() {
            closed = true
            incoming.close()
            outgoing.close()
        }
    }
}

/** Fake platform with a counter for everything opened and closed. */
internal class FakePorts(
    random: SecureRandom,
) {
    val airPlayIdentity: AirPlayIdentity = AirPlayIdentity.generate(random)
    val pairings = InMemoryPairingStore()
    private val mfi = MfiIdentity.load(resource("test-identity.pk8"), resource("test-certificate.p7b"), random)

    /** The iPhone's ends of each RFCOMM dial, in order. */
    val dialed = Channel<Pipe.End>(Channel.UNLIMITED)
    val rfcomm = mutableListOf<Pipe>()
    var dialError: CarPlayError? = null
    var dialMillis = 100L

    /** Dials that were cancelled while connecting, and whether each one's socket was closed (the port contract). */
    var dialsCancelled = 0
    var dialSocketsOpen = 0

    /** When each dial started (virtual time), set by the test's clock. */
    val dialStarts = mutableListOf<Long>()
    var now: () -> Long = { 0L }
    var createGroupError: CarPlayError? = null
    var publishHangs = false

    /** Bluetooth turning on. */
    val radioOn = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** The group's clients as the platform reports them; null: the platform reports nothing (an old Android). */
    val clients = MutableStateFlow<GroupClients?>(null)

    /** The group is lost while collected (our Wi-Fi Direct channel dropped). */
    var groupLost: CarPlayError? = null
    var groupsWorked = 0
    var listenedOn: List<String> = emptyList()
    var credentialsError: CarPlayError? = null

    var groupsCreated = 0
    var createGroupMillis = 0L

    /** createGroup and unpublish fail like an implementation whose own withTimeout expired. */
    var groupTimesOutInside = false
    var unpublishTimesOutInside = false

    /** The pairing store cannot be read (a broken file), so pair-verify M3 throws. */
    var pairingsBroken = false
    var removeGroupMillis = 0L
    var groupsRemoved = 0
    val listeners = mutableListOf<FakeListener>()

    /** Every UDP socket bound, in order. */
    val udp = mutableListOf<FakeUdp>()

    /** Binding a UDP socket fails once this many have been bound; null: never. */
    var udpFailsAfter: Int? = null

    /** The next port handed out for "any free port" (TCP 0 and UDP). */
    private var nextPort = 50_000
    val published = mutableListOf<BonjourService>()
    var unpublished = 0

    /** How long publish takes (DiPlay PR #972: a slow resolver), and whether it then fails. */
    var publishMillis = 0L
    var publishError: CarPlayError? = null

    /** The platform's decoder: what reached it, in order. */
    val screen = FakeScreen()

    /** The platform's sound and microphone. */
    val audio = FakeAudio()
    val microphone = FakeMicrophone()

    val group =
        WifiGroup(
            WifiNetwork("DIRECT-hl-Handlebar", "passphrase16chrs", WifiSecurity.WPA_WPA2, channel = 149),
            addresses = listOf("fe80::1%p2p-wlan0-0", "192.168.49.1"),
            interfaceMac = "aa:bb:cc:dd:ee:01",
        )

    val ports =
        CarPlayPorts(
            bluetooth =
                object : BluetoothPort {
                    override suspend fun dialIap2(): ByteStream {
                        // A socket is open while connecting; the contract says cancellation closes it.
                        dialSocketsOpen++
                        dialStarts += now()
                        try {
                            delay(dialMillis)
                        } catch (e: CancellationException) {
                            dialsCancelled++
                            throw e
                        } finally {
                            dialSocketsOpen--
                        }
                        dialError?.let { throw CarPlayException(it) }
                        val pipe = Pipe().also { rfcomm += it }
                        dialed.send(pipe.phone)
                        return pipe.ours
                    }

                    override fun localAddress(): ByteArray = byteArrayOf(0x22, 0x33, 0x44, 0x55, 0x66, 0x77)

                    override val radioOn: Flow<Unit> = this@FakePorts.radioOn
                },
            wifiDirect =
                object : WifiDirectPort {
                    override suspend fun createGroup(): WifiGroup {
                        delay(createGroupMillis)
                        if (groupTimesOutInside) withTimeout(1) { awaitCancellation() }
                        createGroupError?.let { throw CarPlayException(it) }
                        groupsCreated++
                        return group
                    }

                    override fun clients(): Flow<GroupClients> =
                        flow {
                            groupLost?.let { throw CarPlayException(it) }
                            clients.collect { snapshot ->
                                groupLost?.let { throw CarPlayException(it) }
                                if (snapshot != null) emit(snapshot)
                            }
                        }

                    override fun groupWorked() {
                        groupsWorked++
                    }

                    override suspend fun removeGroup() {
                        delay(removeGroupMillis)
                        groupsRemoved++
                    }
                },
            network =
                object : NetworkPort {
                    override suspend fun listen(
                        addresses: List<String>,
                        preferredPort: Int,
                    ): TcpListener {
                        listenedOn = addresses
                        // Port 0 asks for any free port (the session's event, screen and data listeners).
                        val port = if (preferredPort == 0) nextPort++ else preferredPort
                        return FakeListener(port).also { listeners += it }
                    }

                    override suspend fun bindUdp(): UdpSocket {
                        if (udpFailsAfter?.let { udp.size >= it } == true) {
                            throw CarPlayException(CarPlayError.NetworkFailed("no UDP socket"))
                        }
                        return FakeUdp(nextPort++).also { udp += it }
                    }
                },
            bonjour =
                object : BonjourPort {
                    override suspend fun publish(service: BonjourService) {
                        delay(publishMillis)
                        if (publishHangs) awaitCancellation()
                        publishError?.let { throw CarPlayException(it) }
                        published += service
                    }

                    override suspend fun unpublish() {
                        if (unpublishTimesOutInside) withTimeout(1) { awaitCancellation() }
                        unpublished++
                    }
                },
            credentials = {
                credentialsError?.let { throw CarPlayException(it) }
                CarPlayCredentials(mfi, airPlayIdentity, store)
            },
            screen = screen,
            audio = audio,
            microphone = microphone,
            cipherName = ChaCha20Poly1305.Transformation.JVM,
        )

    private val store =
        object : PairingStore {
            override fun get(controllerId: String): ByteArray? {
                check(!pairingsBroken) { "pairing store unreadable" }
                return pairings.get(controllerId)
            }

            override fun save(
                controllerId: String,
                longTermPublicKey: ByteArray,
            ) = pairings.save(controllerId, longTermPublicKey)
        }

    /** A [ScreenPort] that records every call in order and asks for key frames when the test says. */
    class FakeScreen : ScreenPort {
        /** One call: `configure WxH@fps`, or a frame. */
        sealed interface Call {
            data class Configure(
                val width: Int,
                val height: Int,
                val fps: Int,
            ) : Call

            data class Frame(
                val isConfig: Boolean,
                val senderNanos: Long,
                val payload: String,
            ) : Call
        }

        val calls = mutableListOf<Call>()
        val frames: List<Call.Frame> get() = calls.filterIsInstance<Call.Frame>()
        private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        override val keyFrameRequests: Flow<Unit> = requests

        /** The decoder asks for a key frame (as its listener does, from its own thread: tryEmit). */
        fun needKeyFrame() {
            requests.tryEmit(Unit)
        }

        override fun configure(
            width: Int,
            height: Int,
            framesPerSecond: Int,
        ) {
            calls += Call.Configure(width, height, framesPerSecond)
        }

        override fun frame(
            isConfig: Boolean,
            senderNanos: Long,
            payload: ByteArray,
        ) {
            calls += Call.Frame(isConfig, senderNanos, payload.joinToString("") { "%02x".format(it) })
        }
    }

    /** An [AudioPort] that records every player and what reached it; [failPackets] makes players throw. */
    class FakeAudio : AudioPort {
        val players = mutableListOf<FakePlayer>()
        val ducks = mutableListOf<Boolean>()
        var failPackets = false

        override fun open(stream: AudioStreamInfo): AudioPlayer = FakePlayer(stream).also { players += it }

        override fun duck(ducked: Boolean) {
            ducks += ducked
        }

        inner class FakePlayer(
            val stream: AudioStreamInfo,
        ) : AudioPlayer {
            /** `sequence/timestamp/payload hex`, in order. */
            val packets = mutableListOf<String>()
            var closes = 0

            override fun packet(
                sequence: Int,
                timestamp: Long,
                payload: ByteArray,
            ) {
                check(!failPackets) { "audio device gone" }
                check(closes == 0) { "packet after close" }
                packets += "$sequence/$timestamp/" + payload.joinToString("") { "%02x".format(it) }
            }

            override fun close() {
                closes++
            }
        }
    }

    /** A [MicrophonePort]: the test sends [bodies]; every capture's format and lifetime is recorded. */
    class FakeMicrophone : MicrophonePort {
        var offer = MicrophoneOffer.NONE
        var offerError: CarPlayError? = null

        /** The recorder cannot start. */
        var failure: CarPlayError? = null
        val formats = mutableListOf<MicrophoneFormat>()
        val bodies = Channel<ByteArray>(Channel.UNLIMITED)
        var recording = 0
        var stopped = 0

        override suspend fun offer(): MicrophoneOffer {
            offerError?.let { throw CarPlayException(it) }
            return offer
        }

        override fun capture(format: MicrophoneFormat): Flow<ByteArray> =
            flow {
                formats += format
                recording++
                try {
                    failure?.let { throw CarPlayException(it) }
                    for (body in bodies) emit(body)
                } finally {
                    recording--
                    stopped++
                }
            }
    }

    class FakeListener(
        override val port: Int,
    ) : TcpListener {
        private val incoming = Channel<TcpConnection>(Channel.UNLIMITED)
        var closed = false
            private set

        /** The iPhone (or another station, by [from]) connects; returns its end. */
        fun connect(from: String = "192.168.49.2"): Pipe.End {
            check(!closed) { "listener closed" }
            val pipe = Pipe(from)
            incoming.trySend(pipe.ours)
            return pipe.phone
        }

        override suspend fun accept(): TcpConnection =
            incoming.receiveCatching().getOrNull()
                ?: throw CarPlayException(CarPlayError.NetworkFailed("listener closed"))

        override fun close() {
            closed = true
            incoming.close()
        }
    }

    /** A UDP socket the test feeds; what the receiver sends is kept in [sent]. */
    class FakeUdp(
        override val port: Int,
    ) : UdpSocket {
        private val incoming = Channel<Datagram>(Channel.UNLIMITED)
        val sent = mutableListOf<Datagram>()
        var closed = false
            private set

        /** The iPhone sends [bytes] from [address]:[from]. */
        fun deliver(
            bytes: ByteArray,
            address: String = "192.168.49.2",
            from: Int = 60_000,
        ) {
            incoming.trySend(Datagram(bytes, address, from))
        }

        /** Datagrams the receiver has taken. */
        var received = 0
            private set

        override suspend fun receive(): Datagram? = incoming.receiveCatching().getOrNull()?.also { received++ }

        override suspend fun send(
            bytes: ByteArray,
            address: String,
            port: Int,
        ) {
            if (closed) throw CarPlayException(CarPlayError.NetworkFailed("closed"))
            sent += Datagram(bytes.copyOf(), address, port)
        }

        override fun close() {
            closed = true
            incoming.close()
        }
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mfi/$name")) { "missing $name" }.use { it.readBytes() }
}

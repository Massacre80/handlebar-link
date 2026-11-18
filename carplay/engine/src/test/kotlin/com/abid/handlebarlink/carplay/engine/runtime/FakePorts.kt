package com.abid.handlebarlink.carplay.engine.runtime

import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.BluetoothPort
import com.abid.handlebarlink.carplay.engine.spi.BonjourPort
import com.abid.handlebarlink.carplay.engine.spi.BonjourService
import com.abid.handlebarlink.carplay.engine.spi.ByteStream
import com.abid.handlebarlink.carplay.engine.spi.CarPlayCredentials
import com.abid.handlebarlink.carplay.engine.spi.CarPlayPorts
import com.abid.handlebarlink.carplay.engine.spi.NetworkPort
import com.abid.handlebarlink.carplay.engine.spi.TcpConnection
import com.abid.handlebarlink.carplay.engine.spi.TcpListener
import com.abid.handlebarlink.carplay.engine.spi.WifiDirectPort
import com.abid.handlebarlink.carplay.engine.spi.WifiGroup
import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.iap2.WifiNetwork
import com.abid.handlebarlink.carplay.protocol.iap2.WifiSecurity
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.AirPlayIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.InMemoryPairingStore
import com.abid.handlebarlink.carplay.protocol.pairing.PairingStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    val published = mutableListOf<BonjourService>()
    var unpublished = 0

    val group =
        WifiGroup(
            WifiNetwork("DIRECT-hl-Handlebar", "passphrase16chrs", WifiSecurity.WPA_WPA2, channel = 149),
            ownerAddress = "192.168.49.1",
            interfaceMac = "aa:bb:cc:dd:ee:01",
        )

    val ports =
        CarPlayPorts(
            bluetooth =
                object : BluetoothPort {
                    override suspend fun dialIap2(): ByteStream {
                        delay(dialMillis)
                        dialError?.let { throw CarPlayException(it) }
                        val pipe = Pipe().also { rfcomm += it }
                        dialed.send(pipe.phone)
                        return pipe.ours
                    }

                    override fun localAddress(): ByteArray = byteArrayOf(0x22, 0x33, 0x44, 0x55, 0x66, 0x77)
                },
            wifiDirect =
                object : WifiDirectPort {
                    override suspend fun createGroup(): WifiGroup {
                        delay(createGroupMillis)
                        if (groupTimesOutInside) withTimeout(1) { awaitCancellation() }
                        groupsCreated++
                        return group
                    }

                    override suspend fun removeGroup() {
                        delay(removeGroupMillis)
                        groupsRemoved++
                    }
                },
            network =
                object : NetworkPort {
                    override suspend fun listen(
                        address: String,
                        preferredPort: Int,
                    ): TcpListener = FakeListener(preferredPort).also { listeners += it }
                },
            bonjour =
                object : BonjourPort {
                    override suspend fun publish(service: BonjourService) {
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

    class FakeListener(
        override val port: Int,
    ) : TcpListener {
        private val incoming = Channel<TcpConnection>(Channel.UNLIMITED)
        var closed = false
            private set

        /** The iPhone connects; returns its end. */
        fun connect(): Pipe.End {
            check(!closed) { "listener closed" }
            val pipe = Pipe()
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

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mfi/$name")) { "missing $name" }.use { it.readBytes() }
}

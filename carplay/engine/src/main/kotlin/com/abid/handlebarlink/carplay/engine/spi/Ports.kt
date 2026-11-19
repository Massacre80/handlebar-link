package com.abid.handlebarlink.carplay.engine.spi

import com.abid.handlebarlink.carplay.protocol.iap2.WifiNetwork
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.AirPlayIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.PairingStore
import kotlinx.coroutines.flow.Flow

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
    /**
     * The platform's name for ChaCha20-Poly1305 (ChaCha20Poly1305.Transformation.ANDROID on the phone, so
     * every frame is decrypted natively by BoringSSL).
     */
    public val cipherName: String,
)

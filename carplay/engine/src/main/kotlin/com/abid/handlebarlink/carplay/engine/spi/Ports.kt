package com.abid.handlebarlink.carplay.engine.spi

import com.abid.handlebarlink.carplay.protocol.iap2.WifiNetwork
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.AirPlayIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.PairingStore

// The engine's seams to the platform. `:carplay:engine-android` implements them (RFCOMM, Wi-Fi Direct,
// sockets, mDNS, files); tests use fakes. Every failure is a CarPlayException with a CarPlayError.
// Blocking work happens inside the implementations (their own threads); the engine only suspends.
// Every suspending call must end promptly when its coroutine is cancelled, closing what it was blocked on
// (a blocking socket read or accept does not react to interruption): the attempt's teardown waits for its
// children before it closes the radio and the sockets, so a call that ignores cancellation stalls the teardown.

/** A connected byte stream (RFCOMM socket or TCP connection). */
public interface ByteStream {
    /** Reads into [buffer]; returns the count, or -1 when the peer closed the stream. */
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
     */
    public suspend fun dialIap2(): ByteStream

    /** This phone's Bluetooth address, 6 bytes, sent in identification. */
    public fun localAddress(): ByteArray
}

/** A Wi-Fi Direct group with this phone as owner. */
public data class WifiGroup(
    /** What the iPhone joins. */
    val network: WifiNetwork,
    /** Our address on the group interface (normally 192.168.49.1). */
    val ownerAddress: String,
    /** The group interface's MAC, lower-case "aa:bb:…": our AirPlay receiver id. */
    val interfaceMac: String,
)

/** Wi-Fi Direct. */
public interface WifiDirectPort {
    /** Creates (or reuses) the group with a stable name and passphrase. */
    public suspend fun createGroup(): WifiGroup

    /** Removes the group; idempotent, never throws. */
    public suspend fun removeGroup()
}

/** A bound TCP server. */
public interface TcpListener {
    /** The bound port. */
    public val port: Int

    /** Waits for the next connection. */
    public suspend fun accept(): TcpConnection

    /** Stops listening; idempotent, never throws. */
    public fun close()
}

/** An accepted TCP connection. */
public interface TcpConnection : ByteStream {
    /** The peer's address, for logs and connection limits. */
    public val remoteAddress: String
}

/** Sockets. */
public interface NetworkPort {
    /**
     * Listens on [address], preferring [preferredPort] and then the next ten ports (DiPlay
     * AirPlayPortSelector.kt:17-64). Connections get TCP_NODELAY and keep-alive.
     */
    public suspend fun listen(
        address: String,
        preferredPort: Int,
    ): TcpListener
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
    /** Publishes [service] until [unpublish]. */
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

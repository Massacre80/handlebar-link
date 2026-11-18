package com.abid.handlebarlink.carplay.protocol.pairing

import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import java.security.SecureRandom
import java.util.UUID

/**
 * The receiver's long-term AirPlay identity (DiPlay AirPlayIdentity.kt:6-19): an Ed25519 key pair and a
 * pairing id (lower-case UUID text). Generated once and persisted; the iPhone remembers it after pair-setup.
 * Published as Bonjour `pi`/`pk` and in iAP2 StartSession.
 */
public class AirPlayIdentity(
    /** The Ed25519 key pair. */
    public val keys: Ed25519KeyPair,
    /** Pairing id, e.g. "0f8fad5b-d9cb-469f-a165-70867728950e". */
    public val pairingId: String,
) {
    /** The public key as 64 lower-case hex characters. */
    public val publicKeyHex: String get() = keys.publicKey.joinToString("") { "%02x".format(it) }

    /** Creating identities. */
    public companion object {
        /** A new identity. */
        public fun generate(random: SecureRandom): AirPlayIdentity {
            val uuidBytes = ByteArray(UUID_BYTES).also(random::nextBytes)
            // Version 4, IETF variant (RFC 9562 §5.4), from the injected random source.
            uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0F) or 0x40).toByte()
            uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3F) or 0x80).toByte()
            val high = uuidBytes.copyOfRange(0, 8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            val low = uuidBytes.copyOfRange(8, 16).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            return AirPlayIdentity(Ed25519KeyPair.generate(random), UUID(high, low).toString())
        }

        private const val UUID_BYTES = 16
    }
}

/** The iPhones that completed pair-setup: pairing id → their Ed25519 long-term public key. */
public interface PairingStore {
    /** The stored key of [controllerId], or null. */
    public fun get(controllerId: String): ByteArray?

    /** Remembers [controllerId]'s key. */
    public fun save(
        controllerId: String,
        longTermPublicKey: ByteArray,
    )
}

/** A [PairingStore] in memory (tests, and as a cache in front of persistent storage). */
public class InMemoryPairingStore : PairingStore {
    private val pairings = HashMap<String, ByteArray>()

    override fun get(controllerId: String): ByteArray? = pairings[controllerId]?.copyOf()

    override fun save(
        controllerId: String,
        longTermPublicKey: ByteArray,
    ) {
        pairings[controllerId] = longTermPublicKey.copyOf()
    }
}

package com.abid.handlebarlink.carplay.engine.android.credentials

import com.abid.handlebarlink.carplay.protocol.crypto.Ed25519KeyPair
import com.abid.handlebarlink.carplay.protocol.pairing.AirPlayIdentity
import com.abid.handlebarlink.carplay.protocol.pairing.PairingStore
import com.abid.handlebarlink.core.common.log.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/** Replaces [file] with [text] all at once: written to a sibling, synced, then renamed over it. */
internal fun writeAtomically(
    file: File,
    text: String,
) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(temporary).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    if (!temporary.renameTo(file)) throw IOException("Could not replace ${file.name}")
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

internal fun String.hexOrNull(): ByteArray? {
    if (length % 2 != 0 || any { Character.digit(it, HEX) < 0 }) return null
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(HEX).toByte() }
}

private const val BYTE_MASK = 0xff
private const val HEX = 16

/**
 * The long-term AirPlay identity (Ed25519 seed and pairing id), generated once and kept in no-backup storage: the
 * iPhone remembers it after pair-setup, and a copy restored to another phone would impersonate this receiver.
 * DiPlay keeps the same pair in SharedPreferences (AirPlayPersistence.kt:1543-1557). An unreadable file is replaced
 * by a new identity (the iPhone then pairs again) rather than failing every attempt.
 */
internal class IdentityStore(
    location: () -> File,
    private val random: SecureRandom,
    private val log: Logger,
) {
    /** Resolved on first use: `noBackupFilesDir` creates the directory, which is disk I/O. */
    private val file by lazy(location)

    private var cached: AirPlayIdentity? = null

    /** The cached identity, without touching the disk; null before the first [load]. */
    @Synchronized
    fun peek(): AirPlayIdentity? = cached

    /** Reads the identity, or creates and stores it. Blocking: call from a background thread. */
    @Synchronized
    fun load(): AirPlayIdentity {
        cached?.let { return it }
        val identity = read() ?: create()
        cached = identity
        return identity
    }

    private fun read(): AirPlayIdentity? {
        if (!file.isFile) return null
        val identity = parse(file.readLines(Charsets.UTF_8))
        if (identity == null) log.warn("carplay.android.identity.unreadable", "file" to file.name)
        return identity
    }

    private fun parse(lines: List<String>): AirPlayIdentity? {
        val pairingId = lines.getOrNull(1)?.takeIf { it.isNotBlank() }
        val seed = lines.getOrNull(2)?.hexOrNull()?.takeIf { it.size == Ed25519KeyPair.KEY_BYTES }
        return if (lines.firstOrNull() == VERSION && pairingId != null && seed != null) {
            AirPlayIdentity(Ed25519KeyPair.fromSeed(seed), pairingId)
        } else {
            null
        }
    }

    private fun create(): AirPlayIdentity {
        val identity = AirPlayIdentity.generate(random)
        writeAtomically(file, listOf(VERSION, identity.pairingId, identity.keys.seed.toHex()).joinToString("\n"))
        log.info("carplay.android.identity.created", "pairing_id" to identity.pairingId)
        return identity
    }

    private companion object {
        const val VERSION = "airplay-identity-v1"
    }
}

/**
 * The iPhones that completed pair-setup, persisted, at most [capacity] of them: saving one more evicts the one saved
 * longest ago (re-saving an iPhone makes it the newest). DiPlay's store grows without limit
 * (AirPlayPersistence.kt:1569-1577); the C1 review asked for a bound. Reads come from memory; each change is written
 * whole on [writer] (the I/O thread), so the engine's thread never waits for the disk.
 */
internal class FilePairingStore(
    private val file: File,
    private val capacity: Int,
    private val writer: (Runnable) -> Unit,
    private val log: Logger,
) : PairingStore {
    private val pairings = LinkedHashMap<String, ByteArray>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        if (file.isFile) {
            file.readLines(Charsets.UTF_8).forEach { line ->
                val parts = line.split(' ')
                val id = parts.getOrNull(0)?.hexOrNull()?.toString(Charsets.UTF_8)
                val key = parts.getOrNull(1)?.hexOrNull()
                if (parts.size == 2 && id != null && key?.size == Ed25519KeyPair.KEY_BYTES) pairings[id] = key
            }
            evict()
        }
    }

    /** The stored iPhones, oldest first (tests). */
    @Synchronized
    fun ids(): List<String> = pairings.keys.toList()

    @Synchronized
    override fun get(controllerId: String): ByteArray? = pairings[controllerId]?.copyOf()

    @Synchronized
    override fun save(
        controllerId: String,
        longTermPublicKey: ByteArray,
    ) {
        pairings.remove(controllerId)
        pairings[controllerId] = longTermPublicKey.copyOf()
        evict()
        val text =
            pairings.entries.joinToString("\n") { (id, key) ->
                id.toByteArray(Charsets.UTF_8).toHex() + " " +
                    key.toHex()
            }
        writer(
            Runnable {
                try {
                    writeAtomically(file, text)
                } catch (e: IOException) {
                    log.warn("carplay.android.pairings.write_failed", "error" to e.toString())
                }
            },
        )
    }

    private fun evict() {
        while (pairings.size > capacity) {
            val oldest = pairings.keys.first()
            pairings.remove(oldest)
            log.info("carplay.android.pairings.evicted", "controller" to oldest)
        }
    }
}

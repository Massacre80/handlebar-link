package com.abid.handlebarlink.carplay.engine.android.credentials

import android.content.res.AssetManager
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import com.abid.handlebarlink.carplay.engine.spi.CarPlayCredentials
import com.abid.handlebarlink.carplay.engine.spi.CredentialsPort
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentity
import com.abid.handlebarlink.carplay.protocol.mfi.MfiIdentityException
import com.abid.handlebarlink.carplay.protocol.pairing.PairingStore
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom

/** Reads the MFi files by name; null when the build has none. */
internal fun interface MfiSource {
    fun read(name: String): ByteArray?
}

/**
 * The MFi files from the APK's assets, `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`, where
 * CarLink's APK keeps them; put there at build time from `carplay.mfi.dir` (hl.carplay.mfi), never from git.
 */
internal class AssetMfiSource(
    private val assets: () -> AssetManager,
) : MfiSource {
    override fun read(name: String): ByteArray? =
        try {
            assets().open("$DIRECTORY/$name").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            null
        }

    companion object {
        const val DIRECTORY = "offline-mfi"
        const val KEY_FILE = "identity.pk8"
        const val CERTIFICATE_FILE = "certificate.p7b"
    }
}

/**
 * Loads [CarPlayCredentials] on the I/O thread: the MFi identity from [mfi] (checked by [MfiIdentity.load]), the
 * stored AirPlay identity and the pairing store. Missing or broken MFi files give [CarPlayError.IdentityUnavailable].
 */
internal class AndroidCredentialsPort(
    private val mfi: MfiSource,
    private val identities: IdentityStore,
    private val pairings: () -> PairingStore,
    private val io: CoroutineDispatcher,
    private val log: Logger,
) : CredentialsPort {
    private var mfiIdentity: MfiIdentity? = null
    private var pairingStore: PairingStore? = null

    override suspend fun load(): CarPlayCredentials =
        withContext(io) {
            val identity = loadMfi()
            val airPlay =
                try {
                    identities.load()
                } catch (e: IOException) {
                    throw CarPlayException(
                        CarPlayError.IdentityUnavailable("AirPlay identity could not be stored: $e"),
                        e,
                    )
                }
            val store = pairingStore ?: pairings().also { pairingStore = it }
            CarPlayCredentials(identity, airPlay, store)
        }

    private fun loadMfi(): MfiIdentity {
        mfiIdentity?.let { return it }
        val key = mfi.read(AssetMfiSource.KEY_FILE)
        val certificate = mfi.read(AssetMfiSource.CERTIFICATE_FILE)
        if (key == null || certificate == null) {
            log.warn("carplay.android.mfi.missing", "key" to (key != null), "certificate" to (certificate != null))
            throw CarPlayException(
                CarPlayError.IdentityUnavailable(
                    "the MFi files are not in this build (set carplay.mfi.dir and rebuild)",
                ),
            )
        }
        val identity =
            try {
                MfiIdentity.load(key, certificate, SecureRandom())
            } catch (e: MfiIdentityException) {
                throw CarPlayException(CarPlayError.IdentityUnavailable(e.message ?: "the MFi files are unusable"), e)
            }
        log.info("carplay.android.mfi.loaded", "certificate_bytes" to certificate.size)
        mfiIdentity = identity
        return identity
    }
}

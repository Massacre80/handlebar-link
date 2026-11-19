package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.android.pairing.CarPlayPairing
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * The rider's iPhones for CarPlay: pairing one inside the app (the iPhone pairs from its own Bluetooth settings while
 * this phone is visible), the paired ones, the one CarPlay uses, and forgetting one. The seam over
 * `:carplay:engine-android`'s [CarPlayPairing], so the view models are tested with a fake.
 */
interface IphonePairing {
    /** The pairing screen's state while collected (Bluetooth is watched only then). */
    fun session(): Flow<IphonePairingState>

    /** The paired iPhones the app lists; null when Bluetooth cannot be read (no permission). */
    fun iphones(): Flow<List<PairedIphone>?>

    /** The iPhone the rider chose for CarPlay, or null (CarPlay then uses the only paired iPhone). */
    fun chosen(): Flow<String?>

    /** Uses [address] for CarPlay. */
    suspend fun choose(address: String)

    /** Drops [address] from the app; it stays paired with Android (no public unpair). */
    suspend fun forget(address: String)

    /** Sets this phone's Bluetooth name; false when Android refused. */
    suspend fun rename(name: String): Boolean
}

/** [IphonePairing] by `:carplay:engine-android`. */
class AndroidIphonePairing
    @Inject
    constructor(
        private val pairing: CarPlayPairing,
    ) : IphonePairing {
        override fun session(): Flow<IphonePairingState> = pairing.session()

        override fun iphones(): Flow<List<PairedIphone>?> = pairing.iphones()

        override fun chosen(): Flow<String?> = pairing.chosen()

        override suspend fun choose(address: String) = pairing.choose(address)

        override suspend fun forget(address: String) = pairing.forget(address)

        override suspend fun rename(name: String): Boolean = pairing.rename(name)
    }

/** The iPhone CarPlay uses: the [chosen] one when it is still listed, else the only paired one; null otherwise. */
fun usedIphone(
    iphones: List<PairedIphone>,
    chosen: String?,
): PairedIphone? =
    if (chosen != null) {
        iphones.firstOrNull { it.address.equals(chosen, ignoreCase = true) }
    } else {
        iphones.singleOrNull()
    }

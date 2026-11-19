package com.abid.handlebarlink.carplay.engine.android.pairing

/** An iPhone paired with this phone, as the app lists it. */
public data class PairedIphone(
    /** Its Bluetooth address ("AA:BB:…"). */
    val address: String,
    /** Its Bluetooth name, cleaned; the address when it has none. */
    val name: String,
)

/**
 * Where an in-app pairing stands ([CarPlayPairing.session]). The rider pairs from the iPhone's own Bluetooth
 * settings while this phone is visible (CarLink's PairingSession on upstream/main: the way shipping receivers do it);
 * Android shows its own code dialog while the bond forms.
 */
public sealed interface IphonePairingState {
    /** Nearby devices (BLUETOOTH_CONNECT) is not allowed: Bluetooth cannot be read. */
    public data object NotAllowed : IphonePairingState

    /** Bluetooth is off. */
    public data object BluetoothOff : IphonePairingState

    /**
     * Waiting for an iPhone to pair. [localName] is this phone's Bluetooth name, what the rider taps on the iPhone;
     * [problem] is why the last try did not end in a paired iPhone.
     */
    public data class Waiting(
        /** This phone's Bluetooth name. */
        val localName: String?,
        /** Why the last try did not pair an iPhone, if it did not. */
        val problem: PairingProblem? = null,
    ) : IphonePairingState

    /** A phone is pairing ([name] when Android knows it): Android shows a code to compare with the iPhone's. */
    public data class Pairing(
        /** The phone's Bluetooth address. */
        val address: String,
        /** Its name, when Android knows it. */
        val name: String?,
    ) : IphonePairingState

    /** Paired; its service records are being read to tell an iPhone from another phone. */
    public data class Checking(
        /** The phone's Bluetooth address. */
        val address: String,
        /** Its name, when Android knows it. */
        val name: String?,
    ) : IphonePairingState

    /** An iPhone is paired. */
    public data class Paired(
        /** The paired iPhone. */
        val iphone: PairedIphone,
    ) : IphonePairingState
}

/** Why a pairing did not end in a paired iPhone. */
public sealed interface PairingProblem {
    /** The bond did not form (the code was refused or the dialog timed out). */
    public data class Failed(
        /** The phone that tried, when Android knew its name. */
        val name: String?,
    ) : PairingProblem

    /** A phone paired, but its service records show no iAP2 and its name is not an iPhone's. */
    public data class NotAnIphone(
        /** The phone that paired, when Android knows its name. */
        val name: String?,
    ) : PairingProblem
}

package com.abid.handlebarlink.carplay.engine.android.pairing

import java.util.UUID

/** A device's bond with this phone (BluetoothDevice.BOND_NONE, BOND_BONDING, BOND_BONDED). */
internal enum class Bond { NONE, BONDING, BONDED }

/** What Bluetooth reports while a pairing screen is open, and the session's own timer. */
internal sealed interface PairingEvent {
    /** Bluetooth turned on (with this phone's name) or off (ACTION_STATE_CHANGED). */
    data class Radio(
        val on: Boolean,
        val localName: String?,
    ) : PairingEvent

    /** This phone's Bluetooth name changed (ACTION_LOCAL_NAME_CHANGED). */
    data class LocalName(
        val name: String?,
    ) : PairingEvent

    /** A device's bond changed (ACTION_BOND_STATE_CHANGED), with its name and cached service records. */
    data class BondChanged(
        val address: String,
        val name: String?,
        val bond: Bond,
        val uuids: Set<UUID>,
    ) : PairingEvent

    /** A device's service records arrived (ACTION_UUID after fetchUuidsWithSdp); empty when the query failed. */
    data class Services(
        val address: String,
        val name: String?,
        val uuids: Set<UUID>,
    ) : PairingEvent

    /** No service records came for [address] in time ([PairingTracker.CHECK_MILLIS]). */
    data class CheckTimedOut(
        val address: String,
    ) : PairingEvent
}

/** What the session must do after a step. */
internal sealed interface PairingEffect {
    /** Ask the device for fresh service records (fetchUuidsWithSdp). */
    data class ReadServices(
        val address: String,
    ) : PairingEffect

    /** Start the check's timer: [PairingEvent.CheckTimedOut] after [PairingTracker.CHECK_MILLIS]. */
    data class StartCheckTimer(
        val address: String,
    ) : PairingEffect
}

/** The session's state: what is shown, and this phone's Bluetooth name (shown while waiting). */
internal data class Tracked(
    val shown: IphonePairingState,
    val localName: String?,
)

/** A step's result. */
internal data class PairingStep(
    val state: Tracked,
    val effects: List<PairingEffect> = emptyList(),
)

/**
 * The pairing session as a pure state machine, after CarLink's PairingSession (carlink-core/.../connection/
 * PairingSession.kt on upstream/main): BONDING is a phone pairing, BONDED is checked for an iPhone by its name and
 * service records (fresh ones asked for with fetchUuidsWithSdp), and a bond that falls back to NONE while pairing is
 * a failed try. Two differences, each with a test: an answer without the iAP2 record and without an iPhone name is
 * shown as "not an iPhone" instead of CarLink's unknown kind; and when no service records come at all within
 * [CHECK_MILLIS] the bond is trusted (the rider paired a phone from the iPhone screen; an empty or missing SDP answer
 * proves nothing, docs/LESSONS.md 63). Once an iPhone is paired the screen is done: later bonds change nothing.
 */
internal object PairingTracker {
    /** How long a bonded phone's service records may take before the bond is trusted. */
    const val CHECK_MILLIS = 12_000L

    /**
     * The state when the screen opens. An iPhone already bonded (a re-paired one, or one the rider forgot and is now
     * adding again) opens straight on [IphonePairingState.Paired], so it is chosen (un-forgotten) without the rider
     * having to unpair and pair afresh: the bond makes no new BONDING→BONDED event, so [next] alone would wait forever
     * (owner, 2026-11-23, after a ROM flash left the iPhone bonded).
     */
    fun start(
        allowed: Boolean,
        on: Boolean,
        localName: String?,
        bonded: List<RadioDevice> = emptyList(),
    ): Tracked =
        when {
            !allowed -> {
                Tracked(IphonePairingState.NotAllowed, null)
            }

            !on -> {
                Tracked(IphonePairingState.BluetoothOff, localName)
            }

            else -> {
                bonded
                    .firstOrNull { IphoneEvidence.isIphone(it.name, it.uuids) }
                    ?.let {
                        Tracked(
                            IphonePairingState.Paired(
                                PairedIphone(it.address, IphoneEvidence.display(it.name, it.address)),
                            ),
                            localName,
                        )
                    }
                    ?: Tracked(IphonePairingState.Waiting(localName), localName)
            }
        }

    /** The state after [event]. */
    fun next(
        state: Tracked,
        event: PairingEvent,
    ): PairingStep {
        val shown = state.shown
        return when {
            shown is IphonePairingState.NotAllowed || shown is IphonePairingState.Paired -> PairingStep(state)

            event is PairingEvent.Radio -> radio(state, event)

            event is PairingEvent.LocalName -> PairingStep(Tracked(shown.withName(event.name), event.name))

            // Bonds while Bluetooth is off are leftovers of the stack shutting down.
            shown is IphonePairingState.BluetoothOff -> PairingStep(state)

            event is PairingEvent.BondChanged -> bond(state, event)

            event is PairingEvent.Services -> services(state, event)

            event is PairingEvent.CheckTimedOut -> timedOut(state, event)

            else -> PairingStep(state)
        }
    }

    private fun radio(
        state: Tracked,
        event: PairingEvent.Radio,
    ): PairingStep =
        if (!event.on) {
            PairingStep(state.copy(shown = IphonePairingState.BluetoothOff))
        } else {
            val name = event.localName ?: state.localName
            val shown = state.shown
            val next = if (shown is IphonePairingState.BluetoothOff) IphonePairingState.Waiting(name) else shown
            PairingStep(Tracked(next.withName(name), name))
        }

    private fun bond(
        state: Tracked,
        event: PairingEvent.BondChanged,
    ): PairingStep {
        val current = matching(state.shown, event.address)
        return when {
            event.bond == Bond.BONDING -> {
                PairingStep(state.copy(shown = IphonePairingState.Pairing(event.address, event.name)))
            }

            event.bond == Bond.BONDED && IphoneEvidence.isIphone(event.name, event.uuids) -> {
                paired(state, event.address, event.name)
            }

            event.bond == Bond.BONDED -> {
                PairingStep(
                    state.copy(shown = IphonePairingState.Checking(event.address, event.name)),
                    listOf(PairingEffect.ReadServices(event.address), PairingEffect.StartCheckTimer(event.address)),
                )
            }

            // Back to no bond for the device being paired or checked: the try failed.
            current != null -> {
                waiting(state, PairingProblem.Failed(event.name ?: current.second))
            }

            else -> {
                PairingStep(state)
            }
        }
    }

    private fun services(
        state: Tracked,
        event: PairingEvent.Services,
    ): PairingStep {
        val checking = state.shown as? IphonePairingState.Checking
        val name = event.name ?: checking?.name
        return when {
            checking == null || !checking.address.equals(event.address, ignoreCase = true) -> PairingStep(state)

            IphoneEvidence.isIphone(name, event.uuids) -> paired(state, checking.address, name)

            // A failed query proves nothing: the timer decides.
            event.uuids.isEmpty() -> PairingStep(state)

            else -> waiting(state, PairingProblem.NotAnIphone(name))
        }
    }

    private fun timedOut(
        state: Tracked,
        event: PairingEvent.CheckTimedOut,
    ): PairingStep {
        val checking = state.shown as? IphonePairingState.Checking
        return if (checking != null && checking.address.equals(event.address, ignoreCase = true)) {
            paired(state, checking.address, checking.name)
        } else {
            PairingStep(state)
        }
    }

    private fun paired(
        state: Tracked,
        address: String,
        name: String?,
    ) = PairingStep(
        state.copy(shown = IphonePairingState.Paired(PairedIphone(address, IphoneEvidence.display(name, address)))),
    )

    private fun waiting(
        state: Tracked,
        problem: PairingProblem,
    ) = PairingStep(state.copy(shown = IphonePairingState.Waiting(state.localName, problem)))

    /** The device being paired or checked, and its name, when it is [address]. */
    private fun matching(
        shown: IphonePairingState,
        address: String,
    ): Pair<String, String?>? {
        val current =
            when (shown) {
                is IphonePairingState.Pairing -> shown.address to shown.name
                is IphonePairingState.Checking -> shown.address to shown.name
                else -> null
            }
        return current?.takeIf { it.first.equals(address, ignoreCase = true) }
    }
}

/** This state with this phone's new [name], where it shows it. */
private fun IphonePairingState.withName(name: String?): IphonePairingState =
    if (this is IphonePairingState.Waiting) copy(localName = name) else this

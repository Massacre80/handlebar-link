package com.abid.handlebarlink.carplay.engine.android.pairing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState.BluetoothOff
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState.Checking
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState.NotAllowed
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState.Paired
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState.Pairing
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState.Waiting
import org.junit.Test

class PairingTrackerTest {
    private val iphone = "20:00:00:00:00:01"
    private val other = "30:00:00:00:00:02"
    private val waiting = PairingTracker.start(allowed = true, on = true, localName = "POCO F1")

    private fun Tracked.then(vararg events: PairingEvent): PairingStep =
        events.fold(PairingStep(this)) { step, event -> PairingTracker.next(step.state, event) }

    private fun bond(
        bond: Bond,
        name: String? = "Abid's iPhone",
        address: String = iphone,
        uuids: Set<java.util.UUID> = emptySet(),
    ) = PairingEvent.BondChanged(address, name, bond, uuids)

    @Test
    fun `the screen opens waiting with this phone's name, or off, or not allowed`() {
        assertThat(waiting.shown).isEqualTo(Waiting("POCO F1"))
        assertThat(PairingTracker.start(allowed = true, on = false, localName = null).shown).isEqualTo(BluetoothOff)
        assertThat(PairingTracker.start(allowed = false, on = true, localName = "x").shown).isEqualTo(NotAllowed)
    }

    @Test
    fun `the screen opens paired when an iPhone is already bonded, so a re-pair needs no fresh bond event`() {
        val bonded = listOf(RadioDevice(iphone, "Abid's iPhone", emptySet()))
        assertThat(PairingTracker.start(allowed = true, on = true, localName = "POCO F1", bonded = bonded).shown)
            .isEqualTo(Paired(PairedIphone(iphone, "Abid's iPhone")))
    }

    @Test
    fun `a bonded device that is not an iPhone leaves the screen waiting`() {
        val bonded = listOf(RadioDevice(other, "V6 Pro+", emptySet()))
        assertThat(PairingTracker.start(allowed = true, on = true, localName = "POCO F1", bonded = bonded).shown)
            .isEqualTo(Waiting("POCO F1"))
    }

    @Test
    fun `an iPhone pairing from its settings goes from pairing to paired`() {
        val bonding = waiting.then(bond(Bond.BONDING))
        assertThat(bonding.state.shown).isEqualTo(Pairing(iphone, "Abid's iPhone"))

        val bonded = bonding.state.then(bond(Bond.BONDED))
        assertThat(bonded.state.shown).isEqualTo(Paired(PairedIphone(iphone, "Abid's iPhone")))
        assertThat(bonded.effects).isEmpty()
    }

    @Test
    fun `a phone with another name is checked by its fresh service records`() {
        val bonded = waiting.then(bond(Bond.BONDING, "Abid"), bond(Bond.BONDED, "Abid"))
        assertThat(bonded.state.shown).isEqualTo(Checking(iphone, "Abid"))
        assertThat(bonded.effects)
            .containsExactly(PairingEffect.ReadServices(iphone), PairingEffect.StartCheckTimer(iphone))

        val proven = bonded.state.then(PairingEvent.Services(iphone, "Abid", setOf(HANDS_FREE, IAP2_RECORD)))
        assertThat(proven.state.shown).isEqualTo(Paired(PairedIphone(iphone, "Abid")))
    }

    @Test
    fun `cached records with iAP2 prove an iPhone at once`() {
        val bonded = waiting.then(bond(Bond.BONDED, "Abid", uuids = setOf(IAP2_RECORD)))
        assertThat(bonded.state.shown).isEqualTo(Paired(PairedIphone(iphone, "Abid")))
    }

    @Test
    fun `records without iAP2 from a phone not named iPhone are not an iPhone`() {
        val step =
            waiting.then(
                bond(Bond.BONDED, "Galaxy S24", other),
                PairingEvent.Services(other, "Galaxy S24", setOf(HANDS_FREE)),
            )
        assertThat(step.state.shown).isEqualTo(Waiting("POCO F1", PairingProblem.NotAnIphone("Galaxy S24")))
    }

    @Test
    fun `a failed query proves nothing, and with no answer in time the bond is trusted`() {
        val checking = waiting.then(bond(Bond.BONDED, "Abid"), PairingEvent.Services(iphone, null, emptySet()))
        assertThat(checking.state.shown).isEqualTo(Checking(iphone, "Abid"))

        val trusted = checking.state.then(PairingEvent.CheckTimedOut(iphone))
        assertThat(trusted.state.shown).isEqualTo(Paired(PairedIphone(iphone, "Abid")))
    }

    @Test
    fun `answers and timers for another device change nothing`() {
        val checking = waiting.then(bond(Bond.BONDED, "Abid")).state
        assertThat(checking.then(PairingEvent.Services(other, "x", setOf(HANDS_FREE))).state).isEqualTo(checking)
        assertThat(checking.then(PairingEvent.CheckTimedOut(other)).state).isEqualTo(checking)
        assertThat(checking.then(bond(Bond.NONE, address = other)).state).isEqualTo(checking)
    }

    @Test
    fun `a bond that falls back while pairing is a failed try, and the screen waits again`() {
        val step = waiting.then(bond(Bond.BONDING), bond(Bond.NONE))
        assertThat(step.state.shown).isEqualTo(Waiting("POCO F1", PairingProblem.Failed("Abid's iPhone")))
    }

    @Test
    fun `a new try after a failed one pairs normally`() {
        val step = waiting.then(bond(Bond.BONDING), bond(Bond.NONE), bond(Bond.BONDING), bond(Bond.BONDED))
        assertThat(step.state.shown).isEqualTo(Paired(PairedIphone(iphone, "Abid's iPhone")))
    }

    @Test
    fun `Bluetooth off and on again waits again, and bonds while off are ignored`() {
        val off = waiting.then(PairingEvent.Radio(on = false, localName = null))
        assertThat(off.state.shown).isEqualTo(BluetoothOff)
        assertThat(
            off.state
                .then(bond(Bond.BONDING))
                .state.shown,
        ).isEqualTo(BluetoothOff)

        val on = off.state.then(PairingEvent.Radio(on = true, localName = "CB350RS"))
        assertThat(on.state.shown).isEqualTo(Waiting("CB350RS"))
    }

    @Test
    fun `a new name for this phone shows while waiting and after a failed try`() {
        val renamed = waiting.then(PairingEvent.LocalName("CB350RS"))
        assertThat(renamed.state.shown).isEqualTo(Waiting("CB350RS"))

        val failed = renamed.state.then(bond(Bond.BONDING), bond(Bond.NONE))
        assertThat(failed.state.shown).isEqualTo(Waiting("CB350RS", PairingProblem.Failed("Abid's iPhone")))
    }

    @Test
    fun `once an iPhone is paired the screen is done, later events change nothing`() {
        val paired = waiting.then(bond(Bond.BONDED)).state
        val later =
            paired.then(
                bond(Bond.BONDING, "Galaxy", other),
                PairingEvent.Radio(on = false, localName = null),
                bond(Bond.NONE),
            )
        assertThat(later.state).isEqualTo(paired)
    }

    @Test
    fun `without the permission nothing moves`() {
        val denied = PairingTracker.start(allowed = false, on = true, localName = null)
        assertThat(denied.then(bond(Bond.BONDED)).state.shown).isEqualTo(NotAllowed)
    }
}

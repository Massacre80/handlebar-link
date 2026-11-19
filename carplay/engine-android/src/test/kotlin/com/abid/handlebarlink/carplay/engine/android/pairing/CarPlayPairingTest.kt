package com.abid.handlebarlink.carplay.engine.android.pairing

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.carplay.engine.android.quietLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, advanceTimeBy
class CarPlayPairingTest {
    private val radio = FakePairingRadio()
    private val store = FakeChoiceStore()
    private val abid = RadioDevice("20:00:00:00:00:01", "Abid's iPhone", emptySet())
    private val custom = RadioDevice("20:00:00:00:00:02", "Work", setOf(IAP2_RECORD))
    private val meter = RadioDevice("10:00:00:00:00:01", "HONDA BTU 2040\u0000\u0000", emptySet())
    private val intercom = RadioDevice("10:00:00:00:00:02", "EJEAS V6", setOf(AUDIO_SINK))

    private fun TestScope.pairing() = CarPlayPairing(radio, store, StandardTestDispatcher(testScheduler), quietLogger())

    @Test
    fun `without the permission the session says so and ends, watching nothing`() =
        runTest {
            radio.allowed = false
            pairing().session().test {
                assertThat(awaitItem()).isEqualTo(IphonePairingState.NotAllowed)
                awaitComplete()
            }
            assertThat(radio.listeners).isEqualTo(0)
        }

    @Test
    fun `the session follows a pairing to a paired iPhone and stops watching when left`() =
        runTest {
            pairing().session().test {
                assertThat(awaitItem()).isEqualTo(IphonePairingState.Waiting("POCO F1"))
                runCurrent()
                radio.emit(PairingEvent.BondChanged(abid.address, abid.name, Bond.BONDING, emptySet()))
                assertThat(awaitItem()).isEqualTo(IphonePairingState.Pairing(abid.address, abid.name))
                radio.emit(PairingEvent.BondChanged(abid.address, abid.name, Bond.BONDED, emptySet()))
                assertThat(awaitItem())
                    .isEqualTo(IphonePairingState.Paired(PairedIphone(abid.address, "Abid's iPhone")))
                cancelAndIgnoreRemainingEvents()
            }
            runCurrent()
            assertThat(radio.listeners).isEqualTo(0)
        }

    @Test
    fun `a phone without an iPhone name gets its records read, and is trusted only after the check time`() =
        runTest {
            pairing().session().test {
                skipItems(1)
                runCurrent()
                radio.emit(PairingEvent.BondChanged(abid.address, "Abid", Bond.BONDED, emptySet()))
                assertThat(awaitItem()).isEqualTo(IphonePairingState.Checking(abid.address, "Abid"))
                assertThat(radio.fetched).containsExactly(abid.address)

                advanceTimeBy(PairingTracker.CHECK_MILLIS - 1)
                runCurrent()
                expectNoEvents()

                advanceTimeBy(2)
                assertThat(awaitItem()).isEqualTo(IphonePairingState.Paired(PairedIphone(abid.address, "Abid")))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the paired iPhones leave out other devices and forgotten iPhones, sorted by name`() =
        runTest {
            radio.bonded = listOf(meter, abid, intercom, custom)
            store.stored = IphoneChoices(null, setOf("20:00:00:00:00:09"))
            assertThat(pairing().iphones().first()).isEqualTo(
                listOf(PairedIphone(abid.address, "Abid's iPhone"), PairedIphone(custom.address, "Work")),
            )

            store.stored = IphoneChoices(null, setOf(abid.address.lowercase()))
            assertThat(pairing().iphones().first()).isEqualTo(listOf(PairedIphone(custom.address, "Work")))
        }

    @Test
    fun `without the permission the list is unknown, not empty`() =
        runTest {
            radio.allowed = false
            radio.bonded = listOf(abid)
            assertThat(pairing().iphones().first()).isNull()
        }

    @Test
    fun `the list is read again when a bond changes`() =
        runTest {
            val pairing = pairing()
            pairing.iphones().test {
                assertThat(awaitItem()).isEqualTo(emptyList())
                runCurrent()
                radio.bonded = listOf(abid)
                radio.emit(PairingEvent.BondChanged(abid.address, abid.name, Bond.BONDED, emptySet()))
                assertThat(awaitItem()).isEqualTo(listOf(PairedIphone(abid.address, "Abid's iPhone")))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `choosing stores the iPhone for CarPlay and brings a forgotten one back`() =
        runTest {
            radio.bonded = listOf(abid)
            store.stored = IphoneChoices(null, setOf(abid.address))
            val pairing = pairing()

            pairing.choose(abid.address)

            assertThat(store.stored).isEqualTo(IphoneChoices(abid.address, emptySet()))
            assertThat(pairing.chosen().first()).isEqualTo(abid.address)
            assertThat(pairing.iphones().first()).isEqualTo(listOf(PairedIphone(abid.address, "Abid's iPhone")))
        }

    @Test
    fun `forgetting the chosen iPhone clears the choice and hides it, and the store is read once`() =
        runTest {
            radio.bonded = listOf(abid, custom)
            store.stored = IphoneChoices(abid.address, emptySet())
            val pairing = pairing()
            pairing.chosen().test {
                assertThat(awaitItem()).isEqualTo(abid.address)

                pairing.forget(abid.address)

                assertThat(awaitItem()).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(store.stored).isEqualTo(IphoneChoices(null, setOf(abid.address)))
            assertThat(pairing.iphones().first()).isEqualTo(listOf(PairedIphone(custom.address, "Work")))
            assertThat(store.reads).isEqualTo(1)
        }

    @Test
    fun `forgetting another iPhone keeps the choice`() =
        runTest {
            store.stored = IphoneChoices(abid.address, emptySet())
            val pairing = pairing()

            pairing.forget(custom.address)

            assertThat(pairing.chosen().first()).isEqualTo(abid.address)
        }

    @Test
    fun `renaming trims the name and hands it to Bluetooth, and an invalid name never reaches it`() =
        runTest {
            val pairing = pairing()
            assertThat(pairing.rename("  CB350RS ")).isTrue()
            assertThat(pairing.rename("   ")).isFalse()
            assertThat(pairing.rename("x".repeat(249))).isFalse()
            assertThat(radio.renamed).containsExactly("CB350RS")

            radio.renameWorks = false
            assertThat(pairing.rename("CB350RS")).isFalse()
        }

    @Test
    fun `nothing touches Bluetooth or the store until asked`() =
        runTest {
            pairing()
            assertThat(radio.fetched).isEmpty()
            assertThat(store.reads).isEqualTo(0)
            assertThat(radio.listeners).isEqualTo(0)
        }
}

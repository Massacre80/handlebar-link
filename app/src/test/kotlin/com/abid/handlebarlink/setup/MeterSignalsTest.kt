package com.abid.handlebarlink.setup

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import org.junit.Test

/**
 * The meter list's steady signal (docs/PLAN.md "Stable live indicators"). Times are the scan's elapsed milliseconds;
 * advertisements every 200 ms, as a low-latency scan hears a meter.
 */
class MeterSignalsTest {
    private val address = "D0:B2:70:42:7F:FB"

    /**
     * A meter sitting near the Strong/Good boundary (-60 dBm): packets jump 6-9 dB either side, with a deep fade now
     * and then, as RSSI does from packet to packet.
     */
    private val noisyNearBoundary = listOf(-54, -66, -58, -69, -52, -63, -57, -71, -55, -62, -80, -56, -65, -53, -61)

    private fun levels(
        signals: MeterSignals,
        rssi: List<Int>,
        start: Long = 0,
        every: Long = 200,
    ): List<SignalLevel> = rssi.mapIndexed { i, value -> signals.level(address, value, start + i * every) }

    @Test
    fun `a noisy signal that flipped the level every few packets keeps one level`() {
        val packets = List(10) { noisyNearBoundary }.flatten()
        // Read packet by packet, as before, the word changed many times in these 30 seconds.
        val raw = packets.map(::signalLevel)
        assertThat(raw.zipWithNext().count { (a, b) -> a != b }).isGreaterThan(40)

        val steady = levels(MeterSignals(), packets)

        assertThat(steady.zipWithNext().count { (a, b) -> a != b }).isEqualTo(0)
    }

    @Test
    fun `one deep fade among strong packets changes nothing`() {
        val signals = MeterSignals()
        levels(signals, List(20) { -50 })

        val after = levels(signals, listOf(-92, -50, -51, -50), start = 4_000)

        assertThat(after.toSet()).isEqualTo(setOf(SignalLevel.STRONG))
    }

    @Test
    fun `a real drop shows within a second and reaches Weak after one hold`() {
        val signals = MeterSignals()
        levels(signals, List(25) { -50 })

        // The rider walks away: every packet is now -85 dBm, from 5 s on.
        val after = levels(signals, List(25) { -85 }, start = 5_000)

        val firstChange = after.indexOfFirst { it != SignalLevel.STRONG }
        assertThat(firstChange * 200L).isLessThanOrEqualTo(1_000L)
        val firstWeak = after.indexOfFirst { it == SignalLevel.WEAK }
        assertThat(firstWeak * 200L).isLessThanOrEqualTo(firstChange * 200L + SIGNAL_HOLD_MS + 200L)
        assertThat(after.last()).isEqualTo(SignalLevel.WEAK)
    }

    @Test
    fun `the level moves only once the average is four dB past a boundary`() {
        val signals = MeterSignals()
        levels(signals, List(30) { -66 })
        assertThat(signals.level(address, -66, 6_000)).isEqualTo(SignalLevel.GOOD)

        // -58 is past the -60 boundary, but not by 4 dB: still Good, however long it lasts.
        val near = levels(signals, List(50) { -58 }, start = 6_200)
        assertThat(near.last()).isEqualTo(SignalLevel.GOOD)

        // -55 is 5 dB past it: Strong.
        val past = levels(signals, List(50) { -55 }, start = 16_200)
        assertThat(past.last()).isEqualTo(SignalLevel.STRONG)
    }

    @Test
    fun `a shown level stays for the hold time even when the signal changes at once`() {
        val signals = MeterSignals(timeConstantMillis = 1)
        assertThat(signals.level(address, -50, 0)).isEqualTo(SignalLevel.STRONG)

        assertThat(signals.level(address, -90, 1_000)).isEqualTo(SignalLevel.STRONG)
        assertThat(signals.level(address, -90, SIGNAL_HOLD_MS - 1)).isEqualTo(SignalLevel.STRONG)
        assertThat(signals.level(address, -90, SIGNAL_HOLD_MS)).isEqualTo(SignalLevel.WEAK)
    }

    @Test
    fun `a device heard again after a long gap starts from its new signal`() {
        val signals = MeterSignals()
        levels(signals, List(10) { -50 })

        assertThat(signals.level(address, -85, 60_000)).isEqualTo(SignalLevel.WEAK)
    }

    @Test
    fun `each device has its own signal`() {
        val signals = MeterSignals()
        assertThat(signals.level("A", -50, 0)).isEqualTo(SignalLevel.STRONG)
        assertThat(signals.level("B", -85, 0)).isEqualTo(SignalLevel.WEAK)
        assertThat(signals.level("A", -52, 200)).isEqualTo(SignalLevel.STRONG)
    }

    @Test
    fun `a single packet's level uses the setup's boundaries`() {
        assertThat(signalLevel(-60)).isEqualTo(SignalLevel.GOOD)
        assertThat(signalLevel(-59)).isEqualTo(SignalLevel.STRONG)
        assertThat(signalLevel(-75)).isEqualTo(SignalLevel.WEAK)
        assertThat(signalLevel(-74)).isEqualTo(SignalLevel.GOOD)
    }
}

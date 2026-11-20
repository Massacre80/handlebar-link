package com.abid.handlebarlink.ui.carplay

import android.Manifest
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.core.designsystem.component.MeterTone
import com.abid.handlebarlink.core.designsystem.component.MeterValue
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.ui.permissions.Access
import org.junit.Test
import java.time.Instant

/**
 * Home's BikePlay card, design B "Instrument" (Paper "BikePlay card", "B · 1" … "B · 9"): its state, meter, words and
 * button for each BikePlay status, built from the tab's hero so the two agree.
 */
class BikePlayCardTest {
    private val abid = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
    private val spare = PairedIphone("20:00:00:00:00:02", "iPhone SE")
    private val now = Instant.parse("2026-11-20T10:00:00Z")
    private val line =
        CarPlayLine(R.string.carplay_status_retrying, R.string.carplay_error_timed_out, 8, SignalTone.PROGRESS)

    private fun engine(state: CarPlayState) = CarPlayStatus.Engine(state)

    private fun card(
        status: CarPlayStatus,
        iphones: List<PairedIphone>? = listOf(abid),
        chosen: String? = null,
        quality: LinkQuality? = null,
        bluetoothOn: Boolean = true,
        at: Instant = now,
    ) = bikePlayCard(status, iphones, chosen, line, quality, bluetoothOn, at)

    @Test
    fun `B 1, connected shows the iPhone, the link quality as segments, the channel and Open BikePlay`() {
        val card = card(engine(CarPlayState.Connected("p", now, wifiChannel = 36)), quality = LinkQuality.STRONG)

        assertThat(card.phase).isEqualTo(CardPhase.CONNECTED)
        assertThat(card.word).isEqualTo(R.string.carplay_status_connected)
        assertThat(card.detail).isEqualTo(HeroDetail.Name("Abid's iPhone"))
        assertThat(card.meter).isEqualTo(MeterValue.Segments(3))
        assertThat(card.tone).isEqualTo(MeterTone.GOOD)
        assertThat(card.quality).isEqualTo(LinkQuality.STRONG)
        assertThat(card.channel).isEqualTo(36)
        assertThat(card.action).isEqualTo(TabAction.OPEN_CARPLAY)
        assertThat(primaryAction(TabAction.OPEN_CARPLAY)).isTrue()
    }

    @Test
    fun `the segments follow the link quality, and all three are lit before a verdict`() {
        val connected = engine(CarPlayState.Connected("p", now, wifiChannel = 36))

        assertThat(card(connected, quality = LinkQuality.FAIR).meter).isEqualTo(MeterValue.Segments(2))
        assertThat(card(connected, quality = LinkQuality.WEAK).meter).isEqualTo(MeterValue.Segments(1))
        assertThat(card(connected, quality = null).meter).isEqualTo(MeterValue.Segments(3))
        assertThat(card(connected, quality = null).quality).isNull()
    }

    @Test
    fun `connected with two iPhones and none chosen still opens BikePlay, naming no iPhone`() {
        val card = card(engine(CarPlayState.Connected("p", now)), iphones = listOf(abid, spare))

        assertThat(card.phase).isEqualTo(CardPhase.CONNECTED)
        assertThat(card.detail).isEqualTo(HeroDetail.Text(R.string.bikeplay_card_your_iphone))
        assertThat(card.channel).isNull()
    }

    @Test
    fun `B 2, the start before the first step and step 1 are Starting with the arc at a quarter and no button`() {
        listOf(
            engine(CarPlayState.Idle),
            engine(CarPlayState.Connecting(ConnectStep.LOADING_IDENTITY)),
            engine(CarPlayState.Connecting(ConnectStep.DIALING_PHONE)),
        ).forEach { status ->
            val card = card(status)
            assertThat(card.phase).isEqualTo(CardPhase.STARTING)
            assertThat(card.word).isEqualTo(R.string.carplay_status_starting)
            assertThat(card.detail).isEqualTo(HeroDetail.Text(R.string.carplay_phase_calling))
            assertThat(card.meter).isEqualTo(MeterValue.Progress(0.25f))
            assertThat(card.action).isNull()
        }
    }

    @Test
    fun `B 3, steps 2 to 4 are Connecting, the arc lit to the step with the step's words`() {
        val expected =
            mapOf(
                ConnectStep.AUTHENTICATING to (0.5f to R.string.carplay_phase_checking),
                ConnectStep.WAITING_FOR_PHONE to (0.75f to R.string.carplay_step_waiting),
                ConnectStep.PAIRING to (1f to R.string.carplay_phase_securing),
            )
        expected.forEach { (step, value) ->
            val card = card(engine(CarPlayState.Connecting(step)))
            assertThat(card.phase, "$step").isEqualTo(CardPhase.CONNECTING)
            assertThat(card.word).isEqualTo(R.string.carplay_tag_connecting)
            assertThat(card.meter, "$step").isEqualTo(MeterValue.Progress(value.first))
            assertThat(card.detail, "$step").isEqualTo(HeroDetail.Text(value.second))
            assertThat(card.action).isNull()
        }
    }

    @Test
    fun `B 4, no paired iPhone is Not set up with an empty arc and Add iPhone`() {
        val card = card(engine(CarPlayState.Stopped), iphones = emptyList())

        assertThat(card.phase).isEqualTo(CardPhase.NOT_SET_UP)
        assertThat(card.detail).isEqualTo(HeroDetail.Text(R.string.bikeplay_card_not_set_up))
        assertThat(card.meter).isEqualTo(MeterValue.Empty)
        assertThat(card.action).isEqualTo(TabAction.ADD_IPHONE)
    }

    @Test
    fun `B 5 to 7, what only the rider can fix is Needs you in danger with the fix as the button`() {
        val cases =
            listOf(
                card(engine(CarPlayState.Recovering(CarPlayError.WifiOff, 1, now))) to
                    (R.string.carplay_tab_wifi_off_title to TabAction.TURN_ON_WIFI),
                card(
                    engine(CarPlayState.Recovering(CarPlayError.BluetoothUnavailable("off"), 1, now)),
                    bluetoothOn = false,
                ) to
                    (R.string.carplay_tab_bluetooth_off_title to TabAction.TURN_ON_BLUETOOTH),
                card(CarPlayStatus.NeedsPermission(listOf(Manifest.permission.BLUETOOTH_CONNECT))) to
                    (R.string.carplay_tab_permission_title to TabAction.ALLOW),
            )
        cases.forEach { (card, expected) ->
            assertThat(card.phase).isEqualTo(CardPhase.NEEDS_YOU)
            assertThat(card.word).isEqualTo(R.string.carplay_tag_needs_you)
            assertThat(card.detail).isEqualTo(HeroDetail.Text(expected.first))
            assertThat(card.meter).isEqualTo(MeterValue.Full)
            assertThat(card.tone).isEqualTo(MeterTone.DANGER)
            assertThat(card.action).isEqualTo(expected.second)
            assertThat(primaryAction(expected.second)).isTrue()
        }
    }

    @Test
    fun `Allow becomes Open settings once Android no longer asks, as on the tab`() {
        val card = card(CarPlayStatus.NeedsPermission(listOf(Manifest.permission.RECORD_AUDIO)))

        assertThat(card.withAccess(Access.ASK).action).isEqualTo(TabAction.ALLOW)
        assertThat(card.withAccess(Access.BLOCKED).action).isEqualTo(TabAction.OPEN_SETTINGS)
    }

    @Test
    fun `B 8, a retry counting down is Couldn't connect, its danger arc draining with the pause, and Try now`() {
        val failedAt = now.minusMillis(2_000)
        val state =
            CarPlayState.Recovering(
                CarPlayError.TimedOut(ConnectStep.PAIRING),
                2,
                now.plusMillis(6_000),
                failedAt = failedAt,
            )

        val card = card(engine(state))

        assertThat(card.phase).isEqualTo(CardPhase.RETRYING)
        assertThat(card.word).isEqualTo(R.string.bikeplay_card_couldnt_connect)
        assertThat(card.detail).isEqualTo(HeroDetail.Line(line))
        assertThat(card.meter).isEqualTo(MeterValue.Progress(0.75f, draining = true))
        assertThat(card.tone).isEqualTo(MeterTone.DANGER)
        assertThat(card.action).isEqualTo(TabAction.RETRY_NOW)
        assertThat(primaryAction(TabAction.RETRY_NOW)).isTrue()
        assertThat(primaryAction(TabAction.CANCEL)).isFalse()
        assertThat(primaryAction(TabAction.DISCONNECT)).isFalse()
    }

    @Test
    fun `the pause left is whole when it just failed, empty when due, never outside 0 to 1`() {
        val state =
            CarPlayState.Recovering(
                CarPlayError.TimedOut(ConnectStep.PAIRING),
                1,
                now.plusMillis(4_000),
                failedAt = now,
            )

        assertThat(pauseLeft(state, now)).isEqualTo(1f)
        assertThat(pauseLeft(state, now.plusMillis(1_000))).isEqualTo(0.75f)
        assertThat(pauseLeft(state, now.plusMillis(4_000))).isEqualTo(0f)
        assertThat(pauseLeft(state, now.plusMillis(9_000))).isEqualTo(0f)
        assertThat(pauseLeft(state, now.minusMillis(1_000))).isEqualTo(1f)
        // No pause (Wi-Fi off waits for Wi-Fi): nothing to drain.
        assertThat(pauseLeft(CarPlayState.Recovering(CarPlayError.WifiOff, 1, now), now)).isEqualTo(0f)
    }

    @Test
    fun `B 9, no ride running is BikePlay off, starting again with the ride, with Start BikePlay`() {
        val card = card(engine(CarPlayState.Stopped))

        assertThat(card.phase).isEqualTo(CardPhase.OFF)
        assertThat(card.word).isEqualTo(R.string.bikeplay_card_off)
        assertThat(card.detail).isEqualTo(HeroDetail.Text(R.string.bikeplay_card_starts_when_riding))
        assertThat(card.meter).isEqualTo(MeterValue.Empty)
        assertThat(card.action).isEqualTo(TabAction.START_CARPLAY)
        assertThat(primaryAction(TabAction.START_CARPLAY)).isTrue()
    }

    @Test
    fun `stopped by the rider is BikePlay off too, but never claims it starts with the ride`() {
        val card = card(CarPlayStatus.Held)

        assertThat(card.phase).isEqualTo(CardPhase.OFF)
        assertThat(card.detail).isEqualTo(HeroDetail.Text(R.string.bikeplay_card_held))
        assertThat(card.action).isEqualTo(TabAction.START_CARPLAY)
    }

    @Test
    fun `the card's state always matches the tab's hero`() {
        val statuses =
            listOf(
                engine(CarPlayState.Stopped),
                engine(CarPlayState.Idle),
                engine(CarPlayState.Connecting(ConnectStep.PAIRING)),
                engine(CarPlayState.Connected("p", now)),
                engine(CarPlayState.Recovering(CarPlayError.WifiOff, 1, now)),
                engine(CarPlayState.Recovering(CarPlayError.DialFailed("x"), 1, now.plusMillis(1_500), now)),
                CarPlayStatus.Held,
                CarPlayStatus.Failed,
            )
        val expected =
            mapOf(
                TabPhase.READY to CardPhase.OFF,
                TabPhase.HELD to CardPhase.OFF,
                TabPhase.CONNECTED to CardPhase.CONNECTED,
                TabPhase.NEEDS_YOU to CardPhase.NEEDS_YOU,
            )
        statuses.forEach { status ->
            val hero = carPlayTab(status, listOf(abid), null, line).hero
            val card = card(status)
            expected[hero.phase]?.let { assertThat(card.phase, "$status").isEqualTo(it) }
            if (hero.phase == TabPhase.NEEDS_YOU) assertThat(card.action, "$status").isEqualTo(hero.primary)
        }
    }
}

package com.abid.handlebarlink.ui.carplay

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.core.designsystem.component.HlButtonKind
import com.abid.handlebarlink.core.designsystem.component.HlIcons
import com.abid.handlebarlink.core.designsystem.component.MeterValue
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import org.junit.Test
import java.time.Instant

/**
 * The BikePlay tab (Paper "BikePlay — flow", Tab 1–9): the meter is the card's, and the buttons follow the owner's rule
 * (2026-11-20): what the rider does is lime, dismissing is secondary, ending is red.
 */
class BikePlayTabTest {
    private val abid = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
    private val now = Instant.parse("2026-11-20T10:00:00Z")
    private val line =
        CarPlayLine(R.string.carplay_status_retrying, R.string.carplay_error_dial, 3, SignalTone.PROGRESS)

    private fun engine(state: CarPlayState) = CarPlayStatus.Engine(state)

    private fun tab(
        status: CarPlayStatus,
        iphones: List<PairedIphone> = listOf(abid),
        bluetoothOn: Boolean = true,
        quality: LinkQuality? = null,
    ) = carPlayTab(status, iphones, null, line, bluetoothOn = bluetoothOn, quality = quality, now = now)

    private fun buttons(status: CarPlayStatus) = tabButtons(tab(status).card)

    @Test
    fun `Tab 1, connected offers Open BikePlay without an icon over a red Disconnect`() {
        assertThat(buttons(engine(CarPlayState.Connected("p", now, 36)))).containsExactly(
            TabButton(TabAction.OPEN_CARPLAY, HlButtonKind.PRIMARY),
            TabButton(TabAction.DISCONNECT, HlButtonKind.DESTRUCTIVE),
        )
    }

    @Test
    fun `Tab 2 and 3, starting and connecting offer Cancel as the secondary button`() {
        listOf(engine(CarPlayState.Idle), engine(CarPlayState.Connecting(ConnectStep.WAITING_FOR_PHONE))).forEach {
            assertThat(buttons(it), "$it").containsExactly(TabButton(TabAction.CANCEL, HlButtonKind.SECONDARY))
        }
    }

    @Test
    fun `Tab 4, not set up offers Add iPhone with its plus`() {
        val card = tab(engine(CarPlayState.Stopped), iphones = emptyList()).card
        assertThat(
            tabButtons(card),
        ).containsExactly(TabButton(TabAction.ADD_IPHONE, HlButtonKind.PRIMARY, HlIcons.PlusBold))
        assertThat(card.detail).isEqualTo(HeroDetail.Text(R.string.bikeplay_card_not_set_up))
    }

    @Test
    fun `Tab 5 to 7, the fix is the lime button and nothing else`() {
        val cases =
            mapOf(
                engine(CarPlayState.Recovering(CarPlayError.WifiOff, 1, now)) to TabAction.TURN_ON_WIFI,
                CarPlayStatus.NeedsPermission(listOf("android.permission.BLUETOOTH_CONNECT")) to TabAction.ALLOW,
            )
        cases.forEach { (status, fix) ->
            assertThat(buttons(status), "$status").containsExactly(TabButton(fix, HlButtonKind.PRIMARY))
        }
        // Stopped after an error: the Needs you look, Start BikePlay with its play mark.
        assertThat(buttons(CarPlayStatus.Failed))
            .containsExactly(TabButton(TabAction.START_CARPLAY, HlButtonKind.PRIMARY, HlIcons.Play))
        val bluetooth = engine(CarPlayState.Recovering(CarPlayError.BluetoothUnavailable("off"), 1, now))
        assertThat(tabButtons(tab(bluetooth, bluetoothOn = false).card))
            .containsExactly(TabButton(TabAction.TURN_ON_BLUETOOTH, HlButtonKind.PRIMARY))
        assertThat(tabButtons(tab(bluetooth, bluetoothOn = true).card))
            .containsExactly(TabButton(TabAction.CHOOSE_IPHONE, HlButtonKind.PRIMARY))
    }

    @Test
    fun `Tab 8, a retry counting down offers Try now over Cancel, and its arc drains`() {
        val retrying =
            engine(
                CarPlayState.Recovering(CarPlayError.DialFailed("x"), 2, now.plusMillis(1_000), now.minusMillis(3_000)),
            )
        val tab = tab(retrying)
        assertThat(tabButtons(tab.card)).containsExactly(
            TabButton(TabAction.RETRY_NOW, HlButtonKind.PRIMARY),
            TabButton(TabAction.CANCEL, HlButtonKind.SECONDARY),
        )
        assertThat(tab.card.meter).isEqualTo(MeterValue.Progress(0.25f, draining = true))
    }

    @Test
    fun `Tab 9, BikePlay off offers Start BikePlay with its play mark, held or with no ride`() {
        listOf(engine(CarPlayState.Stopped), CarPlayStatus.Held).forEach {
            assertThat(buttons(it), "$it")
                .containsExactly(TabButton(TabAction.START_CARPLAY, HlButtonKind.PRIMARY, HlIcons.Play))
        }
    }

    @Test
    fun `a build without BikePlay has no button`() {
        assertThat(buttons(CarPlayStatus.NotAvailable)).isEmpty()
    }

    @Test
    fun `the steady link quality lights the segments and the Link row, only while connected`() {
        val connected = tab(engine(CarPlayState.Connected("p", now, 36)), quality = LinkQuality.FAIR)
        assertThat(connected.card.meter).isEqualTo(MeterValue.Segments(2))
        assertThat(connected.link.quality).isEqualTo(LinkQuality.FAIR)
        assertThat(tab(engine(CarPlayState.Idle), quality = LinkQuality.FAIR).link.quality).isEqualTo(null)
    }
}

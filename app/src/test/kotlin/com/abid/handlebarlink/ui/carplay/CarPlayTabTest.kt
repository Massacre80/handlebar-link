package com.abid.handlebarlink.ui.carplay

import android.Manifest
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.ConnectStep
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.android.video.CarPlayVideoStats
import com.abid.handlebarlink.core.designsystem.component.SignalTone
import com.abid.handlebarlink.ui.permissions.Access
import org.junit.Test
import java.time.Instant

/** The CarPlay tab's states (Paper "CarPlay — flow v2", Tab 1–4) from the switch, CarPlay's status and the iPhones. */
class CarPlayTabTest {
    private val abid = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
    private val spare = PairedIphone("20:00:00:00:00:02", "iPhone SE")
    private val line =
        CarPlayLine(R.string.carplay_status_retrying, R.string.carplay_error_dial, 3, SignalTone.PROGRESS)
    private val now = Instant.parse("2026-11-19T10:00:00Z")

    private fun engine(state: CarPlayState) = CarPlayStatus.Engine(state)

    private fun hero(
        status: CarPlayStatus,
        iphones: List<PairedIphone>? = listOf(abid),
        chosen: String? = null,
        enabled: Boolean = true,
    ) = carPlayTab(enabled, status, iphones, chosen, line).hero

    @Test
    fun `no paired iPhone is Not set up with Add iPhone, whether the switch is on or off`() {
        listOf(true, false).forEach { enabled ->
            val hero = hero(engine(CarPlayState.Idle), emptyList(), enabled = enabled)
            assertThat(hero.phase).isEqualTo(TabPhase.NOT_SET_UP)
            assertThat(hero.title).isEqualTo(R.string.carplay_tab_add_title)
            assertThat(hero.primary).isEqualTo(TabAction.ADD_IPHONE)
            assertThat(hero.ghost).isNull()
        }
    }

    @Test
    fun `unknown iPhones (no permission) never claim Not set up`() {
        val hero = hero(CarPlayStatus.NeedsPermission(listOf("p")), iphones = null)
        assertThat(hero.phase).isEqualTo(TabPhase.NEEDS_YOU)
        assertThat(hero.primary).isEqualTo(TabAction.ALLOW)
    }

    @Test
    fun `the switch off with an iPhone is CarLink, offering the beta`() {
        val hero = hero(CarPlayStatus.Off, enabled = false)
        assertThat(hero.phase).isEqualTo(TabPhase.CARLINK)
        assertThat(hero.primary).isEqualTo(TabAction.TURN_ON_BETA)
    }

    @Test
    fun `connecting shows the step of four and Cancel as the ghost`() {
        val hero = hero(engine(CarPlayState.Connecting(ConnectStep.IDENTIFYING)))
        assertThat(hero.phase).isEqualTo(TabPhase.CONNECTING)
        assertThat(hero.title).isEqualTo(R.string.carplay_tab_connecting_title)
        assertThat(hero.detail).isEqualTo(HeroDetail.Step(2, R.string.carplay_phase_checking))
        assertThat(hero.progress).isEqualTo(0.5f)
        assertThat(hero.primary).isNull()
        assertThat(hero.ghost).isEqualTo(TabAction.CANCEL)
    }

    @Test
    fun `the engine's seven steps become the rider's four, in order`() {
        val numbers = ConnectStep.entries.map { connectPhase(it).first }
        assertThat(numbers).isEqualTo(listOf(1, 1, 1, 2, 2, 3, 4))
        assertThat(connectPhase(ConnectStep.WAITING_FOR_PHONE).second).isEqualTo(R.string.carplay_phase_wifi)
        assertThat(connectPhase(ConnectStep.PAIRING).second).isEqualTo(R.string.carplay_phase_securing)
    }

    @Test
    fun `a start before the first step counts as step one`() {
        assertThat(hero(engine(CarPlayState.Idle)).detail).isEqualTo(HeroDetail.Step(1, R.string.carplay_phase_calling))
    }

    @Test
    fun `connected is running, with Open CarPlay and Disconnect, naming the iPhone`() {
        val hero = hero(engine(CarPlayState.Connected("phone", now)))
        assertThat(hero.phase).isEqualTo(TabPhase.CONNECTED)
        assertThat(hero.title).isEqualTo(R.string.carplay_tab_connected_title)
        assertThat(hero.detail).isEqualTo(HeroDetail.Iphone(R.string.carplay_tab_connected_detail, "Abid's iPhone"))
        assertThat(hero.primary).isEqualTo(TabAction.OPEN_CARPLAY)
        assertThat(hero.ghost).isEqualTo(TabAction.DISCONNECT)
        assertThat(hero.tone).isEqualTo(SignalTone.ON)
    }

    @Test
    fun `Wi-Fi off needs you, titled by what is wrong, with Turn on Wi-Fi`() {
        val hero = hero(engine(CarPlayState.Recovering(CarPlayError.WifiOff, 1, now)))
        assertThat(hero.phase).isEqualTo(TabPhase.NEEDS_YOU)
        assertThat(hero.title).isEqualTo(R.string.carplay_tab_wifi_off_title)
        assertThat(hero.primary).isEqualTo(TabAction.TURN_ON_WIFI)
        assertThat(hero.ghost).isNull()
        assertThat(hero.tone).isEqualTo(SignalTone.ERROR)
    }

    @Test
    fun `each error the rider must fix gets its one action, or none when it cannot be fixed here`() {
        assertThat(fixFor(CarPlayError.PermissionMissing("p"))).isEqualTo(TabAction.ALLOW)
        assertThat(fixFor(CarPlayError.BluetoothUnavailable("off"))).isEqualTo(TabAction.TURN_ON_BLUETOOTH)
        assertThat(fixFor(CarPlayError.IdentityUnavailable("missing"))).isNull()
        val identity = hero(engine(CarPlayState.Recovering(CarPlayError.IdentityUnavailable("x"), 1, now)))
        assertThat(identity.title).isEqualTo(R.string.carplay_error_identity)
        assertThat(identity.primary).isNull()
    }

    @Test
    fun `a retry the engine makes by itself is still starting, with the countdown line`() {
        val hero = hero(engine(CarPlayState.Recovering(CarPlayError.DialFailed("x"), 1, now)))
        assertThat(hero.phase).isEqualTo(TabPhase.CONNECTING)
        assertThat(hero.detail).isEqualTo(HeroDetail.Line(line))
        assertThat(hero.ghost).isEqualTo(TabAction.CANCEL)
    }

    @Test
    fun `stopped by the rider offers Start CarPlay, and no ride yet is Ready`() {
        val held = hero(CarPlayStatus.Held)
        assertThat(held.phase).isEqualTo(TabPhase.HELD)
        assertThat(held.primary).isEqualTo(TabAction.START_CARPLAY)

        val ready = hero(engine(CarPlayState.Stopped))
        assertThat(ready.phase).isEqualTo(TabPhase.READY)
        assertThat(ready.detail).isEqualTo(HeroDetail.Iphone(R.string.carplay_tab_ready_detail, "Abid's iPhone"))
    }

    @Test
    fun `two iPhones and none chosen, or a chosen one no longer paired, ask to choose`() {
        assertThat(hero(engine(CarPlayState.Idle), listOf(abid, spare)).primary).isEqualTo(TabAction.CHOOSE_IPHONE)
        assertThat(hero(engine(CarPlayState.Idle), listOf(abid), chosen = "20:00:00:00:00:09").primary)
            .isEqualTo(TabAction.CHOOSE_IPHONE)
        val chosen = hero(engine(CarPlayState.Connected("p", now)), listOf(abid, spare), chosen = spare.address)
        assertThat(chosen.detail).isEqualTo(HeroDetail.Iphone(R.string.carplay_tab_connected_detail, "iPhone SE"))
    }

    @Test
    fun `a failure and a build without CarPlay need you but offer nothing here`() {
        assertThat(hero(CarPlayStatus.Failed).primary).isNull()
        assertThat(hero(CarPlayStatus.Failed).phase).isEqualTo(TabPhase.NEEDS_YOU)
        val unavailable = hero(CarPlayStatus.NotAvailable, emptyList())
        assertThat(unavailable.title).isEqualTo(R.string.carplay_status_not_available)
    }

    @Test
    fun `the iPhone row names the iPhone CarPlay uses and opens the sheet only when there is one`() {
        val connected = carPlayTab(true, engine(CarPlayState.Connected("p", now)), listOf(abid), null, line).iphone
        assertThat(
            connected,
        ).isEqualTo(
            IphoneRow("Abid's iPhone", R.string.carplay_iphone_connected, R.string.carplay_iphone_used_detail, true),
        )
        assertThat(
            carPlayTab(true, CarPlayStatus.Held, listOf(abid), null, line).iphone.hint,
        ).isEqualTo(R.string.carplay_iphone_paired)
        assertThat(carPlayTab(true, CarPlayStatus.Held, emptyList(), null, line).iphone.hasIphones).isEqualTo(false)
        assertThat(carPlayTab(true, CarPlayStatus.Held, listOf(abid, spare), null, line).iphone.name).isNull()
        assertThat(
            carPlayTab(true, CarPlayStatus.Held, null, null, line).iphone.hint,
        ).isEqualTo(R.string.carplay_iphone_unknown)
    }

    @Test
    fun `the Link row shows Bluetooth while calling and checking, Wi-Fi Direct from the join on`() {
        assertThat(linkRow(engine(CarPlayState.Connecting(ConnectStep.AUTHENTICATING))).detail)
            .isEqualTo(R.string.carplay_link_bluetooth)
        assertThat(linkRow(engine(CarPlayState.Connecting(ConnectStep.WAITING_FOR_PHONE))).detail)
            .isEqualTo(R.string.carplay_link_both)
        val connected = linkRow(engine(CarPlayState.Connected("p", now)))
        assertThat(
            connected,
        ).isEqualTo(LinkRow(R.string.carplay_link_connected, R.string.carplay_link_both, live = true))
        assertThat(linkRow(CarPlayStatus.Held).hint).isEqualTo(R.string.carplay_link_off)
    }

    @Test
    fun `connected, the Link row names the Wi-Fi Direct band and channel and the picture's quality`() {
        val clean = CarPlayVideoStats(300, 300, 60.0, dropped = 0, decodeP90Millis = 12.0, recoveries = 0)
        assertThat(linkRow(engine(CarPlayState.Connected("p", now, wifiChannel = 36)), clean)).isEqualTo(
            LinkRow(
                R.string.carplay_link_connected,
                R.string.carplay_link_wifi_5,
                live = true,
                channel = 36,
                quality = LinkQuality.STRONG,
            ),
        )
        val onTwoFour = linkRow(engine(CarPlayState.Connected("p", now, wifiChannel = 6)))
        assertThat(onTwoFour.detail).isEqualTo(R.string.carplay_link_wifi_2_4)
        assertThat(onTwoFour.quality).isNull()
        // Channel 14 is the last 2.4 GHz channel; 36 the first 5 GHz one the port uses.
        assertThat(linkRow(engine(CarPlayState.Connected("p", now, wifiChannel = 14))).detail)
            .isEqualTo(R.string.carplay_link_wifi_2_4)
        // Not connected: no channel and no quality, whatever the last window said.
        assertThat(linkRow(CarPlayStatus.Held, clean).quality).isNull()
    }

    @Test
    fun `the link is strong only with nothing lost and decoding within two frames`() {
        fun quality(
            dropped: Int = 0,
            decode: Double? = 12.0,
            recoveries: Int = 0,
            received: Int = 100,
        ) = linkQuality(CarPlayVideoStats(received, received, 20.0, dropped, decode, recoveries))

        assertThat(quality()).isEqualTo(LinkQuality.STRONG)
        assertThat(quality(decode = null)).isEqualTo(LinkQuality.STRONG)
        assertThat(quality(decode = 34.0)).isEqualTo(LinkQuality.STRONG)
        assertThat(quality(decode = 34.1)).isEqualTo(LinkQuality.FAIR)
        assertThat(quality(dropped = 1)).isEqualTo(LinkQuality.FAIR)
        assertThat(quality(dropped = 4)).isEqualTo(LinkQuality.FAIR)
        // 5 % of the pictures lost, a decoder replaced, or decoding slower than six frames: weak.
        assertThat(quality(dropped = 5)).isEqualTo(LinkQuality.WEAK)
        assertThat(quality(recoveries = 1)).isEqualTo(LinkQuality.WEAK)
        assertThat(quality(decode = 100.0)).isEqualTo(LinkQuality.FAIR)
        assertThat(quality(decode = 100.1)).isEqualTo(LinkQuality.WEAK)
        // No window, or a window with nothing received: no verdict.
        assertThat(linkQuality(null)).isNull()
        assertThat(quality(received = 0)).isNull()
    }

    @Test
    fun `a missing permission names itself, Nearby devices, the microphone, Local network, or all of them`() {
        fun needs(vararg missing: String) = hero(CarPlayStatus.NeedsPermission(missing.toList()))

        assertThat(
            needs(Manifest.permission.NEARBY_WIFI_DEVICES).title,
        ).isEqualTo(R.string.carplay_tab_permission_title)
        val microphone = needs(Manifest.permission.RECORD_AUDIO)
        assertThat(microphone.phase).isEqualTo(TabPhase.NEEDS_YOU)
        assertThat(microphone.tag).isEqualTo(R.string.carplay_tag_needs_you)
        assertThat(microphone.title).isEqualTo(R.string.carplay_tab_microphone_title)
        assertThat(microphone.detail).isEqualTo(HeroDetail.Text(R.string.carplay_tab_microphone_detail))
        assertThat(microphone.primary).isEqualTo(TabAction.ALLOW)
        assertThat(microphone.ghost).isNull()
        assertThat(needs(Manifest.permission.ACCESS_LOCAL_NETWORK).title)
            .isEqualTo(R.string.carplay_tab_local_network_title)
        assertThat(needs(Manifest.permission.ACCESS_LOCAL_NETWORK).detail)
            .isEqualTo(HeroDetail.Text(R.string.carplay_tab_local_network_detail))
        val both = needs(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(both.title).isEqualTo(R.string.carplay_tab_access_title)
        assertThat(both.detail).isEqualTo(HeroDetail.Missing(listOf(CarPlayGrant.NEARBY, CarPlayGrant.MICROPHONE)))
        assertThat(both.primary).isEqualTo(TabAction.ALLOW)
        // The engine's own permission error, for the microphone.
        assertThat(heroTitle(CarPlayError.PermissionMissing(Manifest.permission.RECORD_AUDIO)))
            .isEqualTo(R.string.carplay_tab_microphone_title)
        assertThat(heroTitle(CarPlayError.PermissionMissing(Manifest.permission.BLUETOOTH_CONNECT)))
            .isEqualTo(R.string.carplay_tab_permission_title)
    }

    @Test
    fun `Allow asks while Android still asks, and becomes Open settings once it no longer does`() {
        val microphone = hero(CarPlayStatus.NeedsPermission(listOf(Manifest.permission.RECORD_AUDIO)))

        // Never asked, or refused once with a rationale: Allow (the system dialog again).
        assertThat(microphone.withAccess(Access.ASK).primary).isEqualTo(TabAction.ALLOW)
        // Refused for good: Open settings (the app's permission settings).
        assertThat(microphone.withAccess(Access.BLOCKED).primary).isEqualTo(TabAction.OPEN_SETTINGS)
        assertThat(microphone.withAccess(Access.BLOCKED).title).isEqualTo(R.string.carplay_tab_microphone_title)
        // Granted meanwhile (the status not re-read yet): Allow stays, and asking finds nothing to ask.
        assertThat(microphone.withAccess(Access.GRANTED).primary).isEqualTo(TabAction.ALLOW)
        // Other actions are never turned into Open settings.
        val wifi = hero(CarPlayStatus.Engine(CarPlayState.Recovering(CarPlayError.WifiOff, 1, Instant.EPOCH)))
        assertThat(wifi.withAccess(Access.BLOCKED).primary).isEqualTo(TabAction.TURN_ON_WIFI)
    }

    @Test
    fun `the engine's own missing permission gets the same Needs you as the app's check`() {
        val engine =
            hero(
                CarPlayStatus.Engine(
                    CarPlayState.Recovering(
                        CarPlayError.PermissionMissing(Manifest.permission.RECORD_AUDIO),
                        1,
                        Instant.EPOCH,
                    ),
                ),
            )

        assertThat(engine).isEqualTo(hero(CarPlayStatus.NeedsPermission(listOf(Manifest.permission.RECORD_AUDIO))))
    }

    @Test
    fun `the CarPlay screen row shows the stored orientation, landscape by default`() {
        val tab = carPlayTab(true, CarPlayStatus.Off, emptyList(), null, line)
        val portrait =
            carPlayTab(true, CarPlayStatus.Off, emptyList(), null, line, orientation = CarPlayOrientation.PORTRAIT)

        assertThat(CarPlayUiState().orientation).isEqualTo(CarPlayOrientation.LANDSCAPE)
        assertThat(tab.orientation).isEqualTo(CarPlayOrientation.LANDSCAPE)
        assertThat(portrait.orientation).isEqualTo(CarPlayOrientation.PORTRAIT)
        assertThat(orientationName(CarPlayOrientation.LANDSCAPE)).isEqualTo(R.string.carplay_screen_landscape)
        assertThat(orientationName(CarPlayOrientation.PORTRAIT)).isEqualTo(R.string.carplay_screen_portrait)
    }

    @Test
    fun `the tab's default before loading is CarLink, not a false Not set up`() {
        assertThat(CarPlayUiState().hero.phase).isEqualTo(TabPhase.CARLINK)
        assertThat(CarPlayUiState().hero.detail).isInstanceOf(HeroDetail.Text::class)
    }
}

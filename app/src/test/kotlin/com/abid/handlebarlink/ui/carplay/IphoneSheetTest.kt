package com.abid.handlebarlink.ui.carplay

import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.FakeCarPlayPermissions
import com.abid.handlebarlink.carplay.InMemoryCarPlaySettings
import com.abid.handlebarlink.carplay.carPlayCoordinator
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.core.testing.MainDispatcherRule
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.ui.navigation.CarPlayRoute
import com.abid.handlebarlink.ui.navigation.ForgetIphoneRoute
import com.abid.handlebarlink.ui.navigation.IphoneSheetRoute
import com.abid.handlebarlink.ui.navigation.NavigationState
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.TOP_LEVEL_DESTINATIONS
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The iPhone sheet (Paper "Sheet 6"): the iPhones with the used one checked; Forget only through its dialog. */
class IphoneSheetTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val abid = PairedIphone("20:00:00:00:00:01", "Abid's iPhone")
    private val spare = PairedIphone("20:00:00:00:00:02", "iPhone SE")
    private val pairing = FakeIphonePairing(listOf(abid, spare), chosen = abid.address)
    private val state =
        NavigationState(
            TOP_LEVEL_DESTINATIONS.associate { it.route to NavBackStack<NavKey>(it.route) },
            mutableIntStateOf(0),
        )
    private val navigator =
        Navigator(state).also {
            it.navigate(CarPlayRoute)
            it.navigate(IphoneSheetRoute)
        }

    private fun TestScope.viewModel() =
        IphoneSheetViewModel(
            pairing,
            carPlayCoordinator(
                FakeCarPlayLink(),
                InMemoryCarPlaySettings(),
                FakeCarPlayPermissions(),
                testLogger(RecordingLogSink(), TestTimeProvider(testScheduler)),
            ),
        )

    @Test
    fun `the iPhone CarPlay uses is checked with its status, the others are paired`() {
        val connected = CarPlayStatus.Engine(CarPlayState.Connected("p", Instant.EPOCH))
        val sheet = iphoneSheet(listOf(abid, spare), abid.address, connected)
        assertThat(sheet.iphones.map { it.used }).containsExactly(true, false)
        assertThat(sheet.iphones.map { it.status })
            .containsExactly(R.string.carplay_iphone_connected, R.string.carplay_iphone_paired)
        assertThat(sheet.used).isEqualTo(abid)

        val idle = iphoneSheet(listOf(abid), null, CarPlayStatus.Held)
        assertThat(idle.iphones.single().status).isEqualTo(R.string.sheet_iphone_used)
        assertThat(iphoneSheet(listOf(abid, spare), null, CarPlayStatus.Held).used).isNull()
    }

    @Test
    fun `the sheet's Forget only opens the confirmation, nothing is forgotten by it`() =
        runTest {
            askToForget(navigator, abid)

            assertThat(state.current).isEqualTo(ForgetIphoneRoute(abid.address, abid.name))
            assertThat(pairing.forgotten).isEmpty()
            assertThat(pairing.chosenAddress.value).isEqualTo(abid.address)
        }

    @Test
    fun `confirming forgets the iPhone and closes the dialog and the sheet, back on the tab`() =
        runTest {
            askToForget(navigator, abid)

            confirmForget(viewModel(), navigator, abid.address)

            assertThat(pairing.forgotten).containsExactly(abid.address)
            assertThat(state.current).isEqualTo(CarPlayRoute)
        }

    @Test
    fun `cancel or Back closes the dialog and keeps the iPhone`() =
        runTest {
            askToForget(navigator, abid)

            navigator.goBack()

            assertThat(state.current).isEqualTo(IphoneSheetRoute)
            assertThat(pairing.forgotten).isEmpty()
        }
}

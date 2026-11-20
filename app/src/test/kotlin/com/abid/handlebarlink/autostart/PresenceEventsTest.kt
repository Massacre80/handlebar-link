// Virtual-time controls of kotlinx-coroutines-test (runCurrent).
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.autostart

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.bike.link.test.FakeBikeLink
import com.abid.handlebarlink.bike.link.test.FakeBikeLink.Command
import com.abid.handlebarlink.controls.ButtonPresses
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import com.abid.handlebarlink.data.SelectedBike
import com.abid.handlebarlink.ride.RideCoordinator
import com.abid.handlebarlink.ride.RideLink
import com.abid.handlebarlink.ride.RideStarter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PresenceEventsTest {
    private val bike = SelectedBike("D0:B2:70:42:7F:FB", "HONDA BTU 2040")
    private val meterId = 3
    private val meter = Association(meterId, bike.address)
    private val link = FakeBikeLink()
    private val settings = InMemoryBikeSettings()
    private val presence = FakeCompanionPresence(listOf(meter, Association(4, "00:11:22:33:44:55")))
    private val starts = mutableListOf<Boolean>()
    private var opens = 0

    private fun TestScope.events(): PresenceEvents {
        val io = StandardTestDispatcher(testScheduler)
        val logger = testLogger(RecordingLogSink(), TestTimeProvider(testScheduler))
        val coordinator =
            RideCoordinator(
                settings = settings,
                provider = { RideLink(link) {} },
                buttons = ButtonPresses {},
                io = io,
                scope = backgroundScope,
                time = TestTimeProvider(testScheduler),
                logger = logger,
            )
        return PresenceEvents(
            presence = presence,
            coordinator = coordinator,
            rides =
                object : RideStarter {
                    override fun startInBackground(presentHint: Boolean): Boolean {
                        starts += presentHint
                        return true
                    }

                    override fun showOnScreen(): Boolean {
                        opens++
                        return true
                    }
                },
            io = io,
            scope = backgroundScope,
            logger = logger,
        )
    }

    @Test
    fun `the selected bike appearing tells its link and starts the ride, connecting directly`() =
        runTest {
            settings.bike.value = bike
            val events = events()
            runCurrent()

            events.onAssociation(meterId, present = true)
            runCurrent()

            assertThat(link.commands).containsExactly(Command.Presence(true))
            assertThat(starts).containsExactly(true)
            assertThat(opens).isEqualTo(starts.size)
        }

    @Test
    fun `the selected bike leaving only tells its link, the ride service is left to end itself`() =
        runTest {
            settings.bike.value = bike
            val events = events()
            runCurrent()

            events.onAssociation(meterId, present = false)
            runCurrent()

            assertThat(link.commands).containsExactly(Command.Presence(false))
            assertThat(starts).isEmpty()
            assertThat(opens).isEqualTo(0)
        }

    @Test
    fun `an address report matches the selected bike ignoring case`() =
        runTest {
            settings.bike.value = bike
            val events = events()
            runCurrent()

            events.onAddress(bike.address.lowercase(), present = true)
            runCurrent()

            assertThat(starts).containsExactly(true)
            assertThat(opens).isEqualTo(starts.size)
        }

    @Test
    fun `reports for another device, an unknown association or without a bike change nothing`() =
        runTest {
            val events = events()
            runCurrent()
            events.onAddress(bike.address, present = true)
            runCurrent()

            settings.bike.value = bike
            runCurrent()
            events.onAssociation(4, present = true)
            events.onAssociation(99, present = true)
            events.onAddress("AA:BB:CC:DD:EE:FF", present = true)
            runCurrent()

            assertThat(link.commands).isEmpty()
            assertThat(starts).isEmpty()
            assertThat(opens).isEqualTo(0)
        }
}

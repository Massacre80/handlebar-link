package com.abid.handlebarlink.carplay.engine.android.wifi

import android.Manifest
import android.net.wifi.p2p.WifiP2pManager
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelPlanTest {
    private val f5180 = GroupRequest.Frequency(5180)
    private val f5745 = GroupRequest.Frequency(5745)
    private val f2437 = GroupRequest.Frequency(2437)
    private val f2412 = GroupRequest.Frequency(2412)
    private val f2462 = GroupRequest.Frequency(2462)

    /** What the platform answers, request by request; records every request and pause. */
    private class Platform(
        vararg answers: CreateOutcome,
    ) {
        private val queue = ArrayDeque(answers.toList())
        val requests = mutableListOf<GroupRequest>()
        val pauses = mutableListOf<Long>()

        suspend fun create(request: GroupRequest): CreateOutcome {
            requests += request
            return queue.removeFirstOrNull() ?: CreateOutcome.Created
        }

        suspend fun pause(millis: Long) {
            pauses += millis
        }
    }

    private suspend fun run(
        platform: Platform,
        remembered: Int? = null,
        sdk: Int = 37,
    ) = ChannelPlan.run(ChannelPlan.plan(remembered), sdk, platform::create, platform::pause)

    @Test
    fun `the plan is 36 then 149 then 6, 1, 11 then the system default (DiPlay P2pStartupRecovery, no station)`() {
        assertThat(ChannelPlan.plan(null))
            .containsExactly(f5180, f5745, f2437, f2412, f2462, GroupRequest.SystemDefault)
    }

    @Test
    fun `a remembered channel goes first and is not asked for twice`() {
        assertThat(
            ChannelPlan.plan(5745),
        ).containsExactly(f5745, f5180, f2437, f2412, f2462, GroupRequest.SystemDefault)
        assertThat(ChannelPlan.plan(5200).first()).isEqualTo(GroupRequest.Frequency(5200))
        assertThat(ChannelPlan.plan(2427).first()).isEqualTo(GroupRequest.Frequency(2427))
    }

    @Test
    fun `a remembered DFS or out-of-plan frequency is ignored`() {
        listOf(5260, 5500, 5680, 2472, 2484, 5170, 0).forEach { frequency ->
            assertThat(ChannelPlan.plan(frequency).first()).isEqualTo(f5180)
        }
    }

    @Test
    fun `the first request that is accepted ends the plan`() =
        runTest {
            val platform = Platform(CreateOutcome.Created)
            assertThat(run(platform)).isEqualTo(f5180)
            assertThat(platform.requests).containsExactly(f5180)
        }

    @Test
    fun `BUSY is retried once on the same channel after 500 ms`() =
        runTest {
            val platform = Platform(CreateOutcome.Rejected(WifiP2pManager.BUSY), CreateOutcome.Created)
            assertThat(run(platform)).isEqualTo(f5180)
            assertThat(platform.requests).containsExactly(f5180, f5180)
            assertThat(platform.pauses).containsExactly(500L)
        }

    @Test
    fun `BUSY twice moves on to the next channel`() =
        runTest {
            val busy = CreateOutcome.Rejected(WifiP2pManager.BUSY)
            val platform = Platform(busy, busy, CreateOutcome.Created)
            assertThat(run(platform)).isEqualTo(f5745)
            assertThat(platform.requests).containsExactly(f5180, f5180, f5745)
            assertThat(platform.pauses).containsExactly(500L, 500L)
        }

    @Test
    fun `ERROR on a channel moves on without retrying it`() =
        runTest {
            val error = CreateOutcome.Rejected(WifiP2pManager.ERROR)
            val platform = Platform(error, error, CreateOutcome.Created)
            assertThat(run(platform)).isEqualTo(f2437)
            assertThat(platform.requests).containsExactly(f5180, f5745, f2437)
        }

    @Test
    fun `NO_PERMISSION stops the plan as PermissionMissing for the release's permission`() =
        runTest {
            val noPermission = CreateOutcome.Rejected(WifiP2pManager.NO_PERMISSION)
            val on33 = Platform(noPermission)
            assertFailure { run(on33, sdk = 33) }
                .isInstanceOf<CarPlayException>()
                .prop(CarPlayException::error)
                .isEqualTo(CarPlayError.PermissionMissing(Manifest.permission.NEARBY_WIFI_DEVICES))
            assertThat(on33.requests).containsExactly(f5180)
            assertFailure { run(Platform(noPermission), sdk = 31) }
                .isInstanceOf<CarPlayException>()
                .prop(CarPlayException::error)
                .isEqualTo(CarPlayError.PermissionMissing(Manifest.permission.ACCESS_FINE_LOCATION))
        }

    @Test
    fun `P2P_UNSUPPORTED stops the plan`() =
        runTest {
            val platform = Platform(CreateOutcome.Rejected(WifiP2pManager.P2P_UNSUPPORTED))
            assertFailure { run(platform) }
                .isInstanceOf<CarPlayException>()
                .prop(CarPlayException::error)
                .isInstanceOf<CarPlayError.WifiDirectFailed>()
            assertThat(platform.requests).containsExactly(f5180)
        }

    @Test
    fun `no answer stops the plan as WifiDirectFailed`() =
        runTest {
            val platform = Platform(CreateOutcome.NoAnswer)
            assertFailure { run(platform) }
                .isInstanceOf<CarPlayException>()
                .prop(CarPlayException::error)
                .isInstanceOf<CarPlayError.WifiDirectFailed>()
            assertThat(platform.requests).containsExactly(f5180)
        }

    @Test
    fun `every channel refused is WifiDirectFailed after trying all of them`() =
        runTest {
            val error = CreateOutcome.Rejected(WifiP2pManager.ERROR)
            val platform = Platform(*Array(6) { error })
            assertFailure { run(platform) }
                .isInstanceOf<CarPlayException>()
                .prop(CarPlayException::error)
                .isInstanceOf<CarPlayError.WifiDirectFailed>()
            assertThat(platform.requests).containsExactly(f5180, f5745, f2437, f2412, f2462, GroupRequest.SystemDefault)
            assertThat(platform.pauses.size).isEqualTo(5)
        }
}

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.abid.handlebarlink.carplay.engine.android.wifi

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.abid.handlebarlink.carplay.engine.android.quietLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class GroupRemovalTest {
    private class ChannelLost : Exception("channel lost")

    /** The framework: [shows] says from which info call on a group is reported; removing makes it go away. */
    private class Framework(
        private val shows: Int,
        private val group: LiveGroup = LiveGroup("DIRECT-hl-Handlebar", isOwner = true),
    ) {
        var infoCalls = 0
        var removes = 0

        suspend fun info(): LiveGroup? {
            infoCalls++
            return if (removes == 0 && infoCalls >= shows) group else null
        }

        suspend fun remove(): Boolean {
            removes++
            return true
        }
    }

    private fun removal(framework: Framework) =
        GroupRemoval(framework::info, framework::remove, { it?.startsWith("DIRECT-hl") == true }, quietLogger())

    @Test
    fun `a group still forming when the attempt is cancelled is removed once it shows`() =
        runTest {
            val framework = Framework(shows = 4)

            removal(framework).run(forming = true)

            assertThat(framework.removes).isEqualTo(1)
        }

    @Test
    fun `with no create outstanding an absent group is not waited for`() =
        runTest {
            val framework = Framework(shows = 4)

            removal(framework).run(forming = false)

            assertThat(framework.removes).isEqualTo(0)
            assertThat(framework.infoCalls).isEqualTo(1)
        }

    @Test
    fun `a forming group that never shows is waited for at most a second`() =
        runTest {
            val framework = Framework(shows = Int.MAX_VALUE)

            removal(framework).run(forming = true)

            assertThat(framework.removes).isEqualTo(0)
            assertThat(currentTime).isEqualTo(GroupRemoval.FORMING_POLLS * GroupRemoval.POLL_MILLIS)
        }

    @Test
    fun `another app's group is never removed`() =
        runTest {
            val framework = Framework(shows = 1, group = LiveGroup("DIRECT-xy-Other", isOwner = true))

            removal(framework).run(forming = true)

            assertThat(framework.removes).isEqualTo(0)
        }

    @Test
    fun `a channel lost right after it was checked still ends the client reports`() =
        runTest {
            val lost = MutableSharedFlow<Unit>(extraBufferCapacity = 1) // No replay, as P2pFramework.channelLost.
            val flow =
                callbackFlow<Int> {
                    closeWhenLost(
                        lost,
                        isLive = {
                            // The framework drops the channel just after answering "live".
                            lost.tryEmit(Unit)
                            true
                        },
                        error = { ChannelLost() },
                    )
                    awaitClose()
                }

            assertFailure { withTimeout(1_000) { flow.collect() } }.isInstanceOf<ChannelLost>()
        }
}

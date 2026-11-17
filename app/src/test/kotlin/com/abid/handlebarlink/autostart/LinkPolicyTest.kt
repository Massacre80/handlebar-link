package com.abid.handlebarlink.autostart

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.bike.link.BikeLinkConfig
import com.abid.handlebarlink.core.testing.RecordingLogSink
import com.abid.handlebarlink.core.testing.TestTimeProvider
import com.abid.handlebarlink.core.testing.testLogger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LinkPolicyTest {
    // Lower case, as MacAddress prints it: matched ignoring case.
    private val presence = FakeCompanionPresence(listOf(Association(7, "d0:b2:70:42:7f:fb")))

    private fun TestScope.policy() =
        LinkPolicy(
            presence = presence,
            io = StandardTestDispatcher(testScheduler),
            logger = testLogger(RecordingLogSink(), TestTimeProvider(testScheduler)),
        )

    @Test
    fun `a bike Android watches for lingers out`() =
        runTest {
            assertThat(policy().configFor("D0:B2:70:42:7F:FB")).isEqualTo(BikeLinkConfig(lingerMillis = LINGER_MILLIS))
        }

    @Test
    fun `a bike without an association stays armed`() =
        runTest {
            assertThat(policy().configFor("00:11:22:33:44:55")).isEqualTo(BikeLinkConfig(lingerMillis = null))
        }
}

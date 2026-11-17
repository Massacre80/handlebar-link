package com.abid.handlebarlink.autostart

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.abid.handlebarlink.bike.link.BikeLinkConfig
import org.junit.Test

class PresenceTest {
    private val meter = Association(3, "D0:B2:70:42:7F:FB")
    private val oldMeter = Association(1, "00:11:22:33:44:55")

    @Test
    fun `the selected bike is observed and every other association released`() {
        val plan = planPresence(meter.address, listOf(oldMeter, meter))

        assertThat(plan.observe).isEqualTo(meter)
        assertThat(plan.release).containsExactly(oldMeter)
    }

    @Test
    fun `addresses match ignoring case, as MacAddress prints lower case`() {
        val plan = planPresence(meter.address.lowercase(), listOf(meter))

        assertThat(plan.observe).isEqualTo(meter)
        assertThat(plan.release).isEmpty()
    }

    @Test
    fun `no selected bike releases every association`() {
        val plan = planPresence(null, listOf(oldMeter, meter))

        assertThat(plan.observe).isNull()
        assertThat(plan.release).containsExactly(oldMeter, meter)
    }

    @Test
    fun `a bike without an association leaves nothing observed`() {
        val plan = planPresence("AA:BB:CC:DD:EE:FF", listOf(meter))

        assertThat(plan.observe).isNull()
        assertThat(plan.release).containsExactly(meter)
    }

    @Test
    fun `each release trusts the callbacks it introduced`() {
        // Deprecations from the CompanionDeviceService reference: String in API 33, AssociationInfo in API 36.
        assertThat(listOf(31, 32, 33, 35, 36, 37).map(::presenceCallbacks)).containsExactly(
            PresenceCallbacks.ADDRESS,
            PresenceCallbacks.ADDRESS,
            PresenceCallbacks.ASSOCIATION,
            PresenceCallbacks.ASSOCIATION,
            PresenceCallbacks.EVENTS,
            PresenceCallbacks.EVENTS,
        )
    }

    @Test
    fun `presence events map to appeared, gone, or nothing`() {
        // Constant values from the DevicePresenceEvent reference: BLE_APPEARED 0, BLE_DISAPPEARED 1,
        // BT_CONNECTED 2, BT_DISCONNECTED 3, SELF_MANAGED_APPEARED 4, SELF_MANAGED_DISAPPEARED 5,
        // ASSOCIATION_REMOVED 6.
        assertThat((0..6).map(::presenceOf)).containsExactly(true, false, true, false, null, null, null)
    }

    @Test
    fun `a watched bike lingers ten minutes, any other stays armed`() {
        assertThat(linkConfig(watched = true)).isEqualTo(BikeLinkConfig(lingerMillis = 600_000L))
        assertThat(linkConfig(watched = false)).isEqualTo(BikeLinkConfig(lingerMillis = null))
    }
}

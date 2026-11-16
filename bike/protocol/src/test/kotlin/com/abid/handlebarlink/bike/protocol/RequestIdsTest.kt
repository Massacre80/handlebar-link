package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.containsExactly
import org.junit.Test

/** RoadSync's two counters (assignment/ and datacommunication/commands/ RequestIdGenerator). */
class RequestIdsTest {
    @Test
    fun `assignment ids are pre-incremented, so the first is 1`() {
        val ids = AssignmentRequestIds()

        assertThat(List(3) { ids.next() }).containsExactly(1, 2, 3)
    }

    @Test
    fun `assignment ids wrap from 65535 to 0, as (id + 1) and 0xFFFF does`() {
        val ids = AssignmentRequestIds(start = 0xFFFE)

        assertThat(List(3) { ids.next() }).containsExactly(0xFFFF, 0, 1)
    }

    @Test
    fun `command ids are post-incremented, so the first is 0`() {
        val ids = CommandRequestIds()

        assertThat(List(3) { ids.next() }).containsExactly(0, 1, 2)
    }

    @Test
    fun `command ids wrap from 255 to 0`() {
        val ids = CommandRequestIds(start = 0xFF)

        assertThat(List(2) { ids.next() }).containsExactly(0xFF, 0)
    }
}

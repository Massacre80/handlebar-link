package com.abid.handlebarlink.bike.link.android

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import org.junit.Test

class GattStatusTest {
    @Test
    fun `authentication statuses become AuthenticationFailed`() {
        listOf(5, 15, 137).forEach { status ->
            assertThat(GattStatus.errorFor(GattOp.READ, status)).isEqualTo(LinkError.AuthenticationFailed(status))
        }
    }

    @Test
    fun `other statuses keep the operation`() {
        assertThat(GattStatus.errorFor(GattOp.WRITE, 133)).isEqualTo(LinkError.GattFailed(GattOp.WRITE, 133))
        assertThat(GattStatus.errorFor(GattOp.MTU, 4)).isEqualTo(LinkError.GattFailed(GattOp.MTU, 4))
    }
}

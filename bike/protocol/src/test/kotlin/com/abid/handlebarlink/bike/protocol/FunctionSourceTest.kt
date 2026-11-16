package com.abid.handlebarlink.bike.protocol

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

/** Codes verified on the CB350 on 2026-11-16 (SAB_PROTOCOL.md "Verified on the CB350"). */
class FunctionSourceTest {
    @Test
    fun `each verified press decodes to its switch`() {
        val verified =
            mapOf(
                "01" to HandlebarSwitch.UP,
                "02" to HandlebarSwitch.DOWN,
                "03" to HandlebarSwitch.LEFT,
                "04" to HandlebarSwitch.RIGHT,
                "07" to HandlebarSwitch.HOLD_DOWN,
                "08" to HandlebarSwitch.HOLD_LEFT,
            )

        verified.forEach { (byte, switch) ->
            assertThat(FunctionSource.decode(hex(byte))).isEqualTo(FunctionEvent.Press(switch))
        }
    }

    @Test
    fun `mode codes report whether the meter forwards the switch to the phone`() {
        // On connect the meter sent 10 then 9.
        assertThat(FunctionSource.decode(hex("0A"))).isEqualTo(FunctionEvent.MeterMode(phoneMode = false))
        assertThat(FunctionSource.decode(hex("09"))).isEqualTo(FunctionEvent.MeterMode(phoneMode = true))
    }

    @Test
    fun `HID volume ids are documented but are not presses`() {
        assertThat(FunctionSource.decode(hex("05"))).isEqualTo(FunctionEvent.Other(FunctionId.VOLUME_UP))
        assertThat(FunctionSource.decode(hex("06"))).isEqualTo(FunctionEvent.Other(FunctionId.VOLUME_DOWN))
    }

    @Test
    fun `undocumented values are kept as hex, never thrown`() {
        assertThat(FunctionSource.decode(hex("00"))).isEqualTo(FunctionEvent.Unknown("00"))
        assertThat(FunctionSource.decode(hex("0B"))).isEqualTo(FunctionEvent.Unknown("0B"))
        assertThat(FunctionSource.decode(hex("FF"))).isEqualTo(FunctionEvent.Unknown("FF"))
        assertThat(FunctionSource.decode(ByteArray(0))).isEqualTo(FunctionEvent.Unknown(""))
    }

    @Test
    fun `only the first byte is read, like RoadSync`() {
        assertThat(FunctionSource.decode(hex("01 09"))).isEqualTo(FunctionEvent.Press(HandlebarSwitch.UP))
    }

    @Test
    fun `function ids are 1 to 10 in spec order`() {
        assertThat(FunctionId.entries.map { it.code }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        assertThat(FunctionId.fromCode(11)).isNull()
    }

    @Test
    fun `every switch has its own function id`() {
        val functions = HandlebarSwitch.entries.map { it.function }

        assertThat(functions.toSet().size).isEqualTo(functions.size)
    }
}

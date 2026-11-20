package com.abid.handlebarlink.carplay.engine.android

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

class ReceiverIdsTest {
    // Expected values from Python's hashlib (independent of the code under test):
    // d = sha256(key)[:6]; d[0] = (d[0] & 0xfc) | 0x02  (DiPlay DiPlayBootstrap.kt:45-49).
    private val counting = ByteArray(32) { it.toByte() }
    private val ones = ByteArray(32) { 0xff.toByte() }

    @Test
    fun `the identity id is sha-256 of the public key, locally administered and unicast`() {
        assertThat(ReceiverIds.format(ReceiverIds.fromIdentity(counting))).isEqualTo("62:0d:cd:29:66:c4")
        assertThat(ReceiverIds.format(ReceiverIds.fromIdentity(ones))).isEqualTo("ae:96:13:76:0f:72")
    }

    @Test
    fun `Android's stand-in addresses are not real`() {
        assertThat(ReceiverIds.isReal(ReceiverIds.PLACEHOLDER)).isFalse()
        assertThat(ReceiverIds.isReal("02:00:00:00:00:01")).isFalse()
        assertThat(ReceiverIds.isReal("00:00:00:00:00:00")).isFalse()
        assertThat(ReceiverIds.isReal("not a mac")).isFalse()
        assertThat(ReceiverIds.isReal(null)).isFalse()
        assertThat(ReceiverIds.isReal("A4:5E:60:12:34:56")).isTrue()
    }

    @Test
    fun `the receiver id prefers the interface, then the group owner, then the identity`() {
        val hardware = byteArrayOf(0xa4.toByte(), 0x5e, 0x60, 0x12, 0x34, 0x56)
        assertThat(ReceiverIds.receiverId(hardware, "B4:00:00:00:00:01", counting))
            .isEqualTo("a4:5e:60:12:34:56" to ReceiverIds.Source.HARDWARE)
        assertThat(ReceiverIds.receiverId(null, "B4:00:00:00:00:01", counting))
            .isEqualTo("b4:00:00:00:00:01" to ReceiverIds.Source.GROUP_OWNER)
        // What an app sees on Android 11+: no interface MAC and the owner erased (WifiP2pServiceImpl).
        assertThat(ReceiverIds.receiverId(null, ReceiverIds.PLACEHOLDER, counting))
            // Upper case, as DiPlayBootstrap.deviceId's "%02X" spells it.
            .isEqualTo("62:0D:CD:29:66:C4" to ReceiverIds.Source.IDENTITY)
    }

    @Test
    fun `addresses parse and format round trip`() {
        assertThat(ReceiverIds.format(ReceiverIds.parse("A4:5E:60:12:34:56"))).isEqualTo("a4:5e:60:12:34:56")
    }
}

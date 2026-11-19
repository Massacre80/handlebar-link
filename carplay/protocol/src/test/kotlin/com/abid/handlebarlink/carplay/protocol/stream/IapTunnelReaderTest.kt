package com.abid.handlebarlink.carplay.protocol.stream

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.abid.handlebarlink.carplay.protocol.crypto.ChaCha20Poly1305
import com.abid.handlebarlink.carplay.protocol.hex
import com.abid.handlebarlink.carplay.protocol.pairing.JVM_CIPHER
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * The iAP2 tunnel's data connection against frames sealed by Python's `cryptography` from DiPlay's IapTunnel.kt
 * (src/test/resources/streams/iap_tunnel_vectors.py) and DiPlay's own case (IapTunnelPeerTest.kt:59-66). The size
 * guards are checked on their boundaries with records sealed here by the RFC-tested primitive and framed by hand.
 */
class IapTunnelReaderTest {
    private val key = ByteArray(32) { (it + 1).toByte() } // IapTunnelPeerTest.kt:13

    private fun reader() = IapTunnelReader(key, JVM_CIPHER)

    private fun IapTunnelReader.feed(bytes: ByteArray): List<String> = receive(bytes, 0, bytes.size).map { it.toHex() }

    @Test
    fun `DiPlay's sealed comm record gives its iAP2 bytes`() {
        assertThat(reader().feed(hex(A))).containsExactly("010203")
    }

    @Test
    fun `records across frames come out whole, in order, other types and empty bodies skipped`() {
        val reader = reader()

        val first = reader.feed(hex(B0))
        val second = reader.feed(hex(B1))

        assertThat(first).isEmpty()
        assertThat(second).containsExactly(SYN_ACK, DATA_145)
    }

    @Test
    fun `bytes arriving one at a time give the same records`() {
        val reader = reader()
        val all = hex(B0) + hex(B1)

        val out = all.indices.flatMap { reader.receive(all, it, 1) }.map { it.toHex() }

        assertThat(out).containsExactly(SYN_ACK, DATA_145)
    }

    @Test
    fun `a frame sealed with the wrong counter does not authenticate`() {
        val reader = reader()
        reader.feed(hex(B0))

        assertFailure { reader.feed(hex(B1_COUNTER_0)) }.isInstanceOf<AEADBadTagException>()
    }

    @Test
    fun `a new reader starts the counter at 0 again`() {
        // DiPlay resets it for each connection from the iPhone (IapTunnel.kt:130).
        reader().feed(hex(A))

        assertThat(reader().feed(hex(A))).containsExactly("010203")
    }

    @Test
    fun `a record of exactly the header is accepted and gives nothing, one byte shorter ends the tunnel`() {
        assertThat(reader().feed(sealed(0, record(32, "comm")))).isEmpty()

        assertFailure { reader().feed(sealed(0, record(31, "comm"))) }
            .isInstanceOf<IapTunnelException>()
    }

    @Test
    fun `a record announcing exactly 4 MiB waits for its body, one byte more ends the tunnel`() {
        val limit = IapTunnelReader.MAX_PACKAGE_BYTES

        assertThat(reader().feed(sealed(0, record(limit, "comm")))).isEmpty()
        assertFailure { reader().feed(sealed(0, record(limit + 1, "comm"))) }
            .isInstanceOf<IapTunnelException>()
        // Read unsigned: a size with the top bit set is too large, never a negative length.
        assertFailure { reader().feed(sealed(0, record(0xFFFFFFFFL, "comm"))) }
            .isInstanceOf<IapTunnelException>()
    }

    @Test
    fun `nothing comes out until a whole frame and a whole record header have arrived`() {
        val reader = reader()
        val frame = hex(A)

        assertThat(reader.receive(frame, 0, 2)).isEmpty()
        assertThat(reader.receive(frame, 2, frame.size - 3)).isEmpty()
        assertThat(reader.receive(frame, frame.size - 1, 1).map { it.toHex() }).containsExactly("010203")
    }

    /** A 32-byte record header announcing [size] and [type], no body (IapTunnel.kt:189-205). */
    private fun record(
        size: Long,
        type: String,
    ): ByteArray {
        val header = ByteArray(32)
        for (i in 0 until 4) header[i] = (size ushr (24 - 8 * i)).toByte()
        type.encodeToByteArray().copyInto(header, 16)
        return header
    }

    /** One frame: u16 LE length, sealed with those two bytes as AAD and nonce 4 zero bytes + LE64 [counter]. */
    private fun sealed(
        counter: Long,
        plain: ByteArray,
    ): ByteArray {
        val aad = byteArrayOf(plain.size.toByte(), (plain.size ushr 8).toByte())
        val nonce = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (counter ushr (8 * i)).toByte() }
        return aad + ChaCha20Poly1305(key, JVM_CIPHER).seal(nonce, aad, plain)
    }

    private companion object {
        // iap_tunnel_vectors.py, run 2026-11-19.
        const val A =
            "2300638ad73567c2a9399b4b365097dead3898b3e29459c46c0c29a2860cc0b8b3d1cbd45410ae59b3918fe04cdbeb36" +
                "0a522a8264"
        const val B0 =
            "3800638ad7322387ef7ed3027c1bdb93e37798a8fd950d913a5b71fbdc579ce5ed8eabb434f42b8f903f7fd70e083102" +
                "bebe1ec929bfc3eeffc258882130cd41fc8b462adc6fe4287f04"
        const val B1 =
            "7900f440d6f28b1d5393a8eded45a095904cd371d10cd6c646613fd7b71e7acdffd367e6fc4de2ef9be6f00d4f3869b6" +
                "4eb8265748d6f155a5bb5aff320281b359edbfa706b5f3b120dae6283a3e2a62ce67d1e1b14be691b7cb9a56fb955406" +
                "40f0f1b5aedd9d1b51ab631a716c8cb3de89ab976fd47546057d427d5dbffd2dcef87dbd655027aefd5f82"
        const val B1_COUNTER_0 =
            "7900f71f4181ff5b33a207d6a8cf6884ad253b4cecf98ec568f3d6a2860cc0b8b3dbcad45c922a8391002f52888f994f" +
                "f1f3558c6efa6b4d5f61b8789294912342e9904b60e03d8e1ea72ef152c08780f4b52dcc501c57022286830721c22c3d" +
                "1596f9247ed57d3c79c8f4f2b3d30bfd7681ef0720545b7fc3377df435b64afa4b801bd4f8399c831ae0cc"

        // The iAP2 frames inside B (spec A §7.2 C and E).
        const val SYN_ACK = "ff5a001dc0906300d70104ffff0000000000000a00020b02010c0102d4"
        const val DATA_145 = "ff5a00104091630a59404000061d005d"
    }
}

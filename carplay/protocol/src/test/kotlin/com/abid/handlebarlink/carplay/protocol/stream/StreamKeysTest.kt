package com.abid.handlebarlink.carplay.protocol.stream

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.abid.handlebarlink.carplay.protocol.plist.Plist
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test
import java.math.BigInteger

/** Stream keys against Python's `cryptography` HKDF (src/test/resources/streams/generate_vectors.py). */
class StreamKeysTest {
    private val shared = ByteArray(32) { it.toByte() }

    @Test
    fun `stream connection id 12345 gives the output and input keys Python derives`() {
        // Also in spec C §9.2, computed there with Python's standard library.
        assertThat(StreamKeys.output(shared, 12345).toHex())
            .isEqualTo("7a0a0ebaa095ab14b9366e179f7e3e2eda27ea6f2bf930198acdca4c79d62d9d")
        assertThat(StreamKeys.input(shared, 12345).toHex())
            .isEqualTo("5cc5df99856e50b600793b4da0918b3120d92bac96ff36aec4437e67e5175bbf")
    }

    @Test
    fun `an id above 2 to the 63 is written as its unsigned decimal`() {
        val id = BigInteger("17293822569102704641") // 0xF000000000000001
        val bits = Plist.Integer(id).bits

        assertThat(StreamKeys.salt(bits)).isEqualTo("DataStream-Salt17293822569102704641")
        assertThat(StreamKeys.output(shared, bits).toHex())
            .isEqualTo("3f34934a397992af9cc613d6f3a479dba46d631694c409473adb0e1d6900b96f")
        assertThat(StreamKeys.input(shared, bits).toHex())
            .isEqualTo("df8540e46fb0e7dfba6c2a23f74b615af0fbf333c56857b3fca6ad147322614c")
    }

    @Test
    fun `an 8-byte id read as negative gives the same salt as its unsigned value`() {
        // Apple's 8-byte plist integers are signed; DiPlay prints the Long unsigned (CarPlayMediaEngine.kt:716).
        val negative = Plist.Integer(BigInteger.valueOf(-1)).bits

        assertThat(StreamKeys.salt(negative)).isEqualTo("DataStream-Salt18446744073709551615")
    }
}

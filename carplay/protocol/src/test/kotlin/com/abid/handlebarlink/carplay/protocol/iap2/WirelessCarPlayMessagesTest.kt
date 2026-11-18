package com.abid.handlebarlink.carplay.protocol.iap2

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.abid.handlebarlink.carplay.protocol.hex
import com.abid.handlebarlink.carplay.protocol.toHex
import org.junit.Test

/** Vectors from docs/research/carplay-spec/B-iap2-messages.md §6; each says whether it is a DiPlay test literal. */
class WirelessCarPlayMessagesTest {
    private val livi = WifiNetwork("LIVI", "secret123", WifiSecurity.WPA3_TRANSITION, channel = 36)

    @Test
    fun `Wi-Fi configuration matches DiPlay's LIVI test vector`() {
        // DiPlay Iap2WirelessControlClientTest.kt:54 (test literal).
        assertThat(WirelessCarPlayMessages.wifiConfiguration(livi).encode().toHex()).isEqualTo(
            "404000275703000900014c49564900000e00027365637265743132330000050003030005000424",
        )
    }

    @Test
    fun `StartSession matches DiPlay's LIVI wireless test vector`() {
        // DiPlay Iap2WirelessControlClientTest.kt:64-66 (test literal).
        val message =
            WirelessCarPlayMessages.startSession(
                StartSession(
                    network = livi,
                    ipAddresses = listOf("192.168.2.1"),
                    airPlayPort = 49152,
                    deviceId = "dev-1",
                    publicKeyHex = "aabbcc",
                    sourceVersion = "1.0",
                ),
            )

        assertThat(message.encode().toHex()).isEqualTo(
            "40400060430100350001000900004c49564900000e000173656372657431323300000500022400100003" +
                "3139322e3136382e322e31000005000403000800020000c000000a00036465762d3100000b000461" +
                "61626263630000080005312e3000",
        )
    }

    @Test
    fun `StartSession repeats the address parameter for every address`() {
        val message =
            WirelessCarPlayMessages.startSession(
                StartSession(livi, listOf("192.168.43.1", "fe80::1234"), 7000, "d", "k", "v"),
            )

        val addresses =
            message
                .parameter(1)!!
                .group()
                .filter { it.id == 3 }
                .map { it.string() }

        assertThat(addresses).containsExactly("192.168.43.1", "fe80::1234")
    }

    @Test
    fun `an open network encodes an empty passphrase as a single NUL`() {
        val open = WifiNetwork("Bike", "", WifiSecurity.NONE, 6)

        val passphrase = WirelessCarPlayMessages.wifiConfiguration(open).parameter(2)!!

        assertThat(passphrase.payload.toHex()).isEqualTo("00")
    }

    @Test
    fun `route guidance subscription body matches DiPlay's test vector`() {
        // DiPlay Iap2RouteGuidanceSubscriptionTest.kt:56 (test literal: the body).
        val routeGuidance = WirelessCarPlayMessages.subscriptions()[1].encode()

        assertThat(routeGuidance.copyOfRange(6, routeGuidance.size).toHex()).isEqualTo("00060000002a0004000100040002")
    }

    @Test
    fun `subscriptions match DiPlay's builders`() {
        // Spec B §6.2 D2 (computed with a re-encoder that reproduces DiPlay's test literals).
        assertThat(WirelessCarPlayMessages.subscriptions().map { it.encode().toHex() }).containsExactly(
            "4040002e5000001800000004000100040004000400060004000c0004001a00100001000400000004000100040007",
            "40400014520000060000002a0004000100040002",
            "40400012ae00000400040004000500040006",
            "404000124157000400000004000400040005",
            "4040001e415400040000000400010004000200040003000400040004000b",
        )
    }

    @Test
    fun `identification matches DiPlay's wireless identification`() {
        // Spec B §6.2 D1 (computed, 339 bytes) for DiPlay's Iap2WirelessControlClientTest.identification() config.
        val identity =
            AccessoryIdentity(
                name = "LIVI",
                model = "LIVI",
                manufacturer = "LIVI",
                serialNumber = "0123456",
                firmwareVersion = "1.0.0",
                hardwareVersion = "1.0",
                externalAccessoryProtocol = "com.shilapi.xcertplay",
            )

        val message = WirelessCarPlayMessages.identification(identity, hex("aa bb cc dd ee ff"), "LIVI").encode()

        assertThat(message.size).isEqualTo(339)
        assertThat(message.toHex()).isEqualTo(
            "404001531d01000900004c49564900000900014c49564900000900024c495649" +
                "00000c00033031323334353600000a0004312e302e300000080005312e300000" +
                "200006aa01aa035000500252005203ae00ae0241574159415441564301570300" +
                "260007aa00aa02aa04aa05ea00ea015001520152025204ae014158415543004e" +
                "0d4e0e570200050008000006000900140028000a0005000001001a0001636f6d" +
                "2e7368696c6170692e7863657274706c61790000050002000007000c656e0000" +
                "07000d656e00002e001100060000000000090001626c75650000040002000a00" +
                "03aabbccddeeff00090004626c75650000040005002500180006000000010009" +
                "00014c49564900000400020006000300010004000400040005003a001e000600" +
                "00002a00120001526f75746547756964616e6365000006000200400006000400" +
                "40000600060008000600070040000600080008",
        )
    }

    @Test
    fun `identification leaves out rejected message ids from both lists`() {
        val identity = AccessoryIdentity("a", "b", "c", "d", "e", "f", "g")

        val message =
            WirelessCarPlayMessages.identification(
                identity,
                ByteArray(6),
                "s",
                omitted = setOf(0x4301, 0x4300),
            )

        assertThat(message.parameter(6)!!.u16List().filter { it == 0x4301 }).isEmpty()
        assertThat(message.parameter(7)!!.u16List().filter { it == 0x4300 }).isEmpty()
    }

    @Test
    fun `a rejection of message ids is correctable, one about other parameters or authentication is not`() {
        // DiPlay Iap2IdentificationRetryTest.kt:25 and Iap2IdentificationRejectionTest.kt:19 (test literals).
        val olderIos = Iap2Message.decode(Iap2MessageId.IDENTIFICATION_REJECTED, hex("0006 0006 4301 0006 0007 4300"))
        val other = Iap2Message.decode(Iap2MessageId.IDENTIFICATION_REJECTED, hex("0004001e 00060006415a 00040014"))
        val auth = Iap2Message.decode(Iap2MessageId.IDENTIFICATION_REJECTED, hex("00060006 aa01"))

        assertThat(WirelessCarPlayMessages.correctableRejection(olderIos)).isEqualTo(setOf(0x4301, 0x4300))
        assertThat(WirelessCarPlayMessages.correctableRejection(other)).isNull()
        assertThat(WirelessCarPlayMessages.correctableRejection(auth)).isNull()
    }

    @Test
    fun `a rejection with an empty or an overlong id list is not correctable`() {
        // DiPlay Iap2IdentificationRetryTest: rejected(Iap2Parameter(6, byteArrayOf())).omittable() is null.
        val empty = Iap2Message.decode(Iap2MessageId.IDENTIFICATION_REJECTED, hex("0004 0006"))
        // DiPlay MAX_REJECTED_MESSAGE_IDS = 32 (Iap2IdentificationClient.kt:240).
        val ids = ByteArray(2 * 33) { if (it % 2 == 0) 0x50 else it.toByte() }
        val overlong = Iap2Message(Iap2MessageId.IDENTIFICATION_REJECTED, listOf(Iap2Parameter(6, ids)))

        assertThat(WirelessCarPlayMessages.correctableRejection(empty)).isNull()
        assertThat(WirelessCarPlayMessages.correctableRejection(overlong)).isNull()
    }

    @Test
    fun `a malformed message body is delivered as malformed and the stream goes on`() {
        // DiPlay Iap2WirelessControlClientTest: Iap2Frame(0x4300, byteArrayOf(0)) is still answered.
        val stream = Iap2ControlStream()

        val messages = stream.receive(hex("4040 0007 4300 00" + "4040000a4301 00040000"))

        assertThat(messages.map { it.id }).containsExactly(0x4300, 0x4301)
        assertThat(messages.map { it.malformed }).containsExactly(true, false)
        assertThat(messages[1].parameter(0)!!.payload.size).isEqualTo(0)
    }

    @Test
    fun `authentication messages carry the certificate and signature in parameter 0`() {
        // Spec B §6.1 V6 (derived from DiPlay's test harness bytes 01 02 03 / 04 05 06).
        assertThat(WirelessCarPlayMessages.authenticationCertificate(hex("010203")).encode().toHex())
            .isEqualTo("4040000daa0100070000010203")
        assertThat(WirelessCarPlayMessages.authenticationResponse(hex("040506")).encode().toHex())
            .isEqualTo("4040000daa0300070000040506")
        val request = Iap2Message.decode(0xAA02, hex("0024 0000") + ByteArray(32) { 7 })
        assertThat(WirelessCarPlayMessages.challenge(request).size).isEqualTo(32)
        assertFailure { WirelessCarPlayMessages.challenge(Iap2Message(0xAA02, emptyList())) }
            .isInstanceOf<Iap2ProtocolException>()
    }

    @Test
    fun `the control stream splits and reassembles messages across link payloads`() {
        // DiPlay Iap2ProtocolTest.kt:33-41 (test literals).
        val stream = Iap2ControlStream()
        val bytes = hex("4040000a4300 00040000 4040000b4301 0005000001")

        val first = stream.receive(bytes.copyOfRange(0, 3))
        val rest = stream.receive(bytes.copyOfRange(3, bytes.size))

        assertThat(first).isEmpty()
        assertThat(rest.map { it.id }).containsExactly(0x4300, 0x4301)
        assertThat(rest[1].parameter(5)).isNull()
        assertThat(rest[1].parameter(0)!!.u8()).isEqualTo(1)
        val big = WirelessCarPlayMessages.startSession(StartSession(livi, listOf("10.0.0.1"), 7000, "d", "k", "v"))
        val chunks = Iap2ControlStream.chunks(big, 10)
        assertThat(
            chunks
                .flatMap { stream.receive(it) }
                .single()
                .encode()
                .toHex(),
        ).isEqualTo(big.encode().toHex())
    }

    @Test
    fun `the control stream skips bytes until a message start`() {
        val stream = Iap2ControlStream()

        val messages = stream.receive(hex("00 40 01 40 40 00 06 1d 00"))

        assertThat(messages.single().id).isEqualTo(Iap2MessageId.START_IDENTIFICATION)
    }

    @Test
    fun `malformed parameters and strings are refused`() {
        assertFailure { Iap2Message.decode(1, hex("0009 0000 41")) }.isInstanceOf<Iap2ProtocolException>()
        assertFailure { Iap2Message.decode(1, hex("0003 0000")) }.isInstanceOf<Iap2ProtocolException>()
        assertFailure { Iap2Message.decode(1, hex("00")) }.isInstanceOf<Iap2ProtocolException>()
        assertFailure { Iap2Parameter(0, hex("41 00 42 00")).string() }.isInstanceOf<Iap2ProtocolException>()
        assertFailure { Iap2Parameter(0, hex("41")).string() }.isInstanceOf<Iap2ProtocolException>()
        assertFailure { Iap2Parameter(0, hex("01 02")).u8() }.isInstanceOf<Iap2ProtocolException>()
        assertFailure { Iap2Parameter(0, hex("01 02 03")).u16List() }.isInstanceOf<Iap2ProtocolException>()
        assertFailure { iap2Message(1) { string(0, "a\u0000b") } }.isInstanceOf<IllegalArgumentException>()
        assertFailure { iap2Message(1) { u8(0, 256) } }.isInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `numbers are big-endian`() {
        val message =
            iap2Message(1) {
                u16(0, 0x1234)
                u32(1, 0x89ABCDEFL)
            }

        assertThat(message.parameter(0)!!.u16()).isEqualTo(0x1234)
        assertThat(message.parameter(1)!!.payload.toHex()).isEqualTo("89abcdef")
    }
}

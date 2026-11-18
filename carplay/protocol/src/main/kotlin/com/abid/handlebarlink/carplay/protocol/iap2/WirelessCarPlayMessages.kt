package com.abid.handlebarlink.carplay.protocol.iap2

// The control messages of the wireless CarPlay bootstrap over Bluetooth, as DiPlay sends them
// (spec B §2-§3; DiPlay transport/Iap2IdentificationClient.kt:300-455, iap2/message/Iap2Messages.kt:55-167,
// transport/Iap2WirelessControlClient.kt). Order on the wire:
//   iPhone 1D00 → us 1D01 → iPhone 1D02 (or 1D03, one corrected retry)
//   iPhone AA00 → us AA01 (certificate) → iPhone AA02 (challenge) → us AA03 (signature) → iPhone AA05
//   us 5000, 5200, AE00, 4157, 4154 (subscriptions)
//   iPhone 5702 → us 5703 (Wi-Fi), iPhone 4300 → us 4301 (start session), iPhone 4E0E → us 5703 again

/** Message ids of the wireless bootstrap (DiPlay iap2/catalog/Iap2Endpoint.kt). */
public object Iap2MessageId {
    /** iPhone: start identification. */
    public const val START_IDENTIFICATION: Int = 0x1D00

    /** Us: identification information. */
    public const val IDENTIFICATION_INFORMATION: Int = 0x1D01

    /** iPhone: identification accepted. */
    public const val IDENTIFICATION_ACCEPTED: Int = 0x1D02

    /** iPhone: identification rejected, listing what it refused. */
    public const val IDENTIFICATION_REJECTED: Int = 0x1D03

    /** iPhone: send your certificate. */
    public const val REQUEST_AUTHENTICATION_CERTIFICATE: Int = 0xAA00

    /** Us: the MFi certificate. */
    public const val AUTHENTICATION_CERTIFICATE: Int = 0xAA01

    /** iPhone: sign this challenge. */
    public const val REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE: Int = 0xAA02

    /** Us: the signature. */
    public const val AUTHENTICATION_RESPONSE: Int = 0xAA03

    /** iPhone: authentication failed. */
    public const val AUTHENTICATION_FAILED: Int = 0xAA04

    /** iPhone: authentication succeeded. */
    public const val AUTHENTICATION_SUCCEEDED: Int = 0xAA05

    /** iPhone: CarPlay is available; answered with [CARPLAY_START_SESSION]. */
    public const val CARPLAY_AVAILABILITY: Int = 0x4300

    /** Us: join this network and connect to AirPlay here. */
    public const val CARPLAY_START_SESSION: Int = 0x4301

    /** iPhone: wireless CarPlay state update (logged only). */
    public const val WIRELESS_CARPLAY_UPDATE: Int = 0x4E0D

    /** iPhone: its transport identifiers; answered with the Wi-Fi configuration again. */
    public const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION: Int = 0x4E0E

    /** iPhone: which network should I join? */
    public const val REQUEST_ACCESSORY_WIFI_CONFIGURATION: Int = 0x5702

    /** Us: the network. */
    public const val ACCESSORY_WIFI_CONFIGURATION: Int = 0x5703
}

/** Wi-Fi security of the network we offer (DiPlay Iap2WirelessControlClient.kt:295-301). */
public enum class WifiSecurity(
    /** The wire value. */
    public val code: Int,
) {
    /** Open network. */
    NONE(0),

    /** WPA or WPA2 personal. */
    WPA_WPA2(2),

    /** WPA3 with WPA2 transition. */
    WPA3_TRANSITION(3),

    /** WPA3 only. */
    WPA3_ONLY(4),
}

/** The Wi-Fi Direct group the iPhone joins. */
public data class WifiNetwork(
    /** Network name. */
    val ssid: String,
    /** Passphrase; empty for an open network. */
    val passphrase: String,
    /** Security. */
    val security: WifiSecurity,
    /** Channel number (0..255). */
    val channel: Int,
) {
    override fun toString(): String = "WifiNetwork(ssid=$ssid, security=$security, channel=$channel)"
}

/** Who we are, for identification. */
public data class AccessoryIdentity(
    /** Name shown on the iPhone. */
    val name: String,
    /** Model identifier. */
    val model: String,
    /** Manufacturer. */
    val manufacturer: String,
    /** Serial number. */
    val serialNumber: String,
    /** Firmware version. */
    val firmwareVersion: String,
    /** Hardware version. */
    val hardwareVersion: String,
    /** Our external accessory protocol name (no CarPlay EA flag is claimed). */
    val externalAccessoryProtocol: String,
    /** Language tag, e.g. "en". */
    val language: String = "en",
)

/** Builders and parsers for the wireless bootstrap messages. */
public object WirelessCarPlayMessages {
    /** Route guidance component id (DiPlay Iap2IdentificationClient.kt:391, subscription Iap2Messages.kt:290). */
    private const val ROUTE_GUIDANCE_COMPONENT = 42
    private const val ROUTE_NAME_LENGTH = 64
    private const val ROUTE_STORAGE = 8

    /** What we send: base list without 0xAE03, plus 0x5703 (Iap2IdentificationClient.kt:333-334, :412-427). */
    public val MESSAGES_SENT: List<Int> =
        listOf(
            0xAA01,
            0xAA03,
            0x5000,
            0x5002,
            0x5200,
            0x5203,
            0xAE00,
            0xAE02,
            0x4157,
            0x4159,
            0x4154,
            0x4156,
            0x4301,
            0x5703,
        )

    /** What we accept (Iap2IdentificationClient.kt:428-453). */
    public val MESSAGES_RECEIVED: List<Int> =
        listOf(
            0xAA00,
            0xAA02,
            0xAA04,
            0xAA05,
            0xEA00,
            0xEA01,
            0x5001,
            0x5201,
            0x5202,
            0x5204,
            0xAE01,
            0x4158,
            0x4155,
            0x4300,
            0x4E0D,
            0x4E0E,
            0x5702,
        )

    /**
     * IdentificationInformation for the Bluetooth bootstrap, parameter for parameter as DiPlay
     * (Iap2IdentificationClient.kt:320-401). [omitted] are message ids the iPhone rejected.
     */
    public fun identification(
        identity: AccessoryIdentity,
        bluetoothMac: ByteArray,
        ssid: String,
        omitted: Set<Int> = emptySet(),
    ): Iap2Message {
        require(bluetoothMac.size == 6) { "Bluetooth MAC must be 6 bytes" }
        return iap2Message(Iap2MessageId.IDENTIFICATION_INFORMATION) {
            string(0, identity.name)
            string(1, identity.model)
            string(2, identity.manufacturer)
            string(3, identity.serialNumber)
            string(4, identity.firmwareVersion)
            string(5, identity.hardwareVersion)
            u16List(6, MESSAGES_SENT - omitted)
            u16List(7, MESSAGES_RECEIVED - omitted)
            u8(8, 0) // power providing capability: none on wireless
            u16(9, 20) // maximum current drawn from the device, as DiPlay
            group(10) {
                u8(0, 1)
                string(1, identity.externalAccessoryProtocol)
                u8(2, 0)
            }
            string(12, identity.language)
            strings(13, listOf(identity.language))
            group(17) {
                // Bluetooth transport component
                u16(0, 0)
                string(1, "blue")
                none(2)
                bytes(3, bluetoothMac)
                string(4, "blue")
                none(5)
            }
            group(24) {
                // Wireless CarPlay transport component
                u16(0, 1)
                string(1, ssid)
                none(2)
                u16(3, 1)
                none(4)
                none(5)
            }
            group(30) {
                // Route guidance display component
                u16(0, ROUTE_GUIDANCE_COMPONENT)
                string(1, "RouteGuidance")
                u16(2, ROUTE_NAME_LENGTH)
                u16(4, ROUTE_NAME_LENGTH)
                u16(6, ROUTE_STORAGE)
                u16(7, ROUTE_NAME_LENGTH)
                u16(8, ROUTE_STORAGE)
            }
        }
    }

    /**
     * The message ids a 0x1D03 refused, when they can be corrected by leaving them out of our lists
     * (Iap2IdentificationClient.kt:251-299): only parameters 6 and 7, at least one and at most
     * [MAX_REJECTED_IDS] ids, and never the authentication ids. Null when the rejection is about something else.
     */
    public fun correctableRejection(rejection: Iap2Message): Set<Int>? {
        val onlyMessageLists =
            rejection.parameters.isNotEmpty() && rejection.parameters.all { it.id == 6 || it.id == 7 }
        val ids = if (onlyMessageLists) rejectedIds(rejection) else null
        return ids?.takeIf { set ->
            set.isNotEmpty() && set.size <= MAX_REJECTED_IDS &&
                set.none { it in AUTHENTICATION_IDS }
        }
    }

    /** DiPlay's MAX_REJECTED_MESSAGE_IDS (Iap2IdentificationClient.kt:240). */
    private const val MAX_REJECTED_IDS = 32

    private val AUTHENTICATION_IDS =
        Iap2MessageId.REQUEST_AUTHENTICATION_CERTIFICATE..Iap2MessageId.AUTHENTICATION_SUCCEEDED

    private fun rejectedIds(rejection: Iap2Message): Set<Int>? =
        try {
            rejection.parameters.flatMap { it.u16List() }.toSet()
        } catch (_: Iap2ProtocolException) {
            null
        }

    /** AuthenticationCertificate: the certificate file bytes as they are (LocalMfiAuthenticationClient.kt:25-30). */
    public fun authenticationCertificate(certificate: ByteArray): Iap2Message =
        iap2Message(Iap2MessageId.AUTHENTICATION_CERTIFICATE) { bytes(0, certificate) }

    /** The 32-byte challenge of a 0xAA02 (Iap2MfiAuthenticationClient.kt:51-54). */
    public fun challenge(request: Iap2Message): ByteArray =
        request.parameter(0)?.payload ?: throw Iap2ProtocolException("Challenge request without parameter 0")

    /** AuthenticationResponse: the raw 64-byte r‖s signature. */
    public fun authenticationResponse(signature: ByteArray): Iap2Message =
        iap2Message(Iap2MessageId.AUTHENTICATION_RESPONSE) { bytes(0, signature) }

    /** The five subscriptions sent after authentication (Iap2Messages.kt:278-313). */
    public fun subscriptions(): List<Iap2Message> =
        listOf(
            iap2Message(0x5000) {
                group(0) { listOf(1, 4, 6, 12, 26).forEach(::none) }
                group(1) { listOf(0, 1, 7).forEach(::none) }
            },
            iap2Message(0x5200) {
                u16(0, ROUTE_GUIDANCE_COMPONENT)
                none(1)
                none(2)
            },
            iap2Message(0xAE00) { listOf(4, 5, 6).forEach(::none) },
            iap2Message(0x4157) { listOf(0, 4, 5).forEach(::none) },
            iap2Message(0x4154) { listOf(0, 1, 2, 3, 4, 11).forEach(::none) },
        )

    /** AccessoryWiFiConfigurationInformation (Iap2Messages.kt:155-167); [bssid] is optional. */
    public fun wifiConfiguration(
        network: WifiNetwork,
        bssid: ByteArray? = null,
    ): Iap2Message =
        iap2Message(Iap2MessageId.ACCESSORY_WIFI_CONFIGURATION) {
            if (bssid != null) bytes(0, bssid)
            string(1, network.ssid)
            string(2, network.passphrase)
            u8(3, network.security.code)
            u8(4, network.channel)
        }

    /**
     * CarPlayStartSession, wireless form (Iap2Messages.kt:55-100): the network, our addresses on it, the
     * AirPlay port (u32), our receiver id, the Ed25519 public key as lower-case hex text, the source version.
     */
    public fun startSession(session: StartSession): Iap2Message =
        iap2Message(Iap2MessageId.CARPLAY_START_SESSION) {
            group(1) {
                string(0, session.network.ssid)
                string(1, session.network.passphrase)
                u8(2, session.network.channel)
                session.ipAddresses.forEach { string(3, it) }
                u8(4, session.network.security.code)
            }
            u32(2, session.airPlayPort.toLong())
            string(3, session.deviceId)
            string(4, session.publicKeyHex)
            string(5, session.sourceVersion)
        }
}

/** Where the iPhone finds our AirPlay receiver: the content of CarPlayStartSession. */
public data class StartSession(
    /** The Wi-Fi Direct group. */
    val network: WifiNetwork,
    /** Our addresses on it, preferred first. */
    val ipAddresses: List<String>,
    /** The AirPlay TCP port. */
    val airPlayPort: Int,
    /** Our receiver id (the P2P interface MAC, "aa:bb:…"). */
    val deviceId: String,
    /** Our Ed25519 AirPlay public key, lower-case hex. */
    val publicKeyHex: String,
    /** The AirPlay source version we report. */
    val sourceVersion: String,
) {
    init {
        require(airPlayPort in 1..0xFFFF) { "AirPlay port out of range" }
        require(ipAddresses.isNotEmpty()) { "StartSession needs at least one address" }
    }
}

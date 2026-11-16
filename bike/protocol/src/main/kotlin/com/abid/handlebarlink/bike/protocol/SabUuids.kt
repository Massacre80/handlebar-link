package com.abid.handlebarlink.bike.protocol

import java.util.UUID

/**
 * Every GATT service and characteristic the Honda meter ("HONDA BTU …") and the phone use.
 * Section numbers refer to docs/protocol/SAB_PROTOCOL.md.
 */
public object SabUuids {
    // §2.1 Vehicle Information Service: also what the meter advertises (§1.1).

    /** Vehicle Information Service; the UUID the meter advertises (§1.1, §2.1). */
    public val VEHICLE_INFO_SERVICE: UUID = UUID.fromString("4d87b1ea-528b-4798-b002-e2e1442a2e86")

    /** Vehicle Type, 1 byte: 0 automotive, 1 motorcycle (§2.1). */
    public val VEHICLE_TYPE: UUID = UUID.fromString("5738696d-45b3-475f-81d8-cc685d56ec18")

    /** Display Capability, 1-byte bitmap (§2.1); see [DisplayCapability]. */
    public val DISPLAY_CAPABILITY: UUID = UUID.fromString("0ad39e0b-b78d-4252-9917-1aa31a915da7")

    /** Switch Type, 1 byte (§2.1). */
    public val SWITCH_TYPE: UUID = UUID.fromString("ddd14e97-636d-4338-a798-94cf7ce13667")

    /** Category Id, 1 byte (§2.1). */
    public val CATEGORY_ID: UUID = UUID.fromString("3f033888-0211-472e-9405-6525d4e4ddc7")

    /** Series Code, uint16 little-endian (§1.2, §2.1); see [SeriesCode]. */
    public val SERIES_CODE: UUID = UUID.fromString("5074e0e8-006f-41ac-aa0f-de6e4649a830")

    /** Destination Id, 1 byte market code (§2.1). */
    public val DESTINATION_ID: UUID = UUID.fromString("55f7910b-8cda-458b-9b54-8a7da8cd79d4")

    // §2.1, §5.2 Assignment Service.

    /** Assignment Service: tells the meter which switch functions the phone wants (§5.2). */
    public val ASSIGNMENT_SERVICE: UUID = UUID.fromString("f0746e98-61d6-4570-be7e-7635c65c21cb")

    /** Assignment Control: requests are written, results come back as notifications (§5.2). */
    public val ASSIGNMENT_CONTROL: UUID = UUID.fromString("4f5fbe6b-474b-4820-833f-95bfbf525d9f")

    // §2.1, §5.3, §6 Data Communication Service.

    /** Data Communication Service: switch presses in, display commands out (§5.3, §6). */
    public val DATA_SERVICE: UUID = UUID.fromString("8536e103-6771-4d4b-9702-287cb5e1340f")

    /** Data Control Point: commands are written, receipts come back as notifications (§6.1). */
    public val DATA_CONTROL_POINT: UUID = UUID.fromString("d2e23443-f1ad-4293-a70b-a9fd163f7b00")

    /** Function Notification Source: one notification byte per switch press (§5.3). */
    public val FUNCTION_SOURCE: UUID = UUID.fromString("6c060578-d1d9-460a-b86f-eb97f01b2227")

    // §2.1, §3 Security Service: not needed on the CB350 ("Verified"), listed so it is never guessed.

    /** Security Service: optional RSA/AES key exchange (§3). Not used: the CB350 works without it. */
    public val SECURITY_SERVICE: UUID = UUID.fromString("592c1017-9c7b-4a35-aba6-f268592fb8fc")

    // §2.1, §8 DFU Service: firmware update. Never written, never subscribed (see SabSafety).

    /** DFU (firmware update) service. Nothing in it may be written or subscribed to (§8). */
    public val DFU_SERVICE: UUID = UUID.fromString("0ea1bd92-5c48-41ad-9b8f-70da1bd08f35")

    /** DFU Firmware Version, 2 bytes `[major, minor]`; the only DFU characteristic that is safe to read (§2.1). */
    public val DFU_FIRMWARE_VERSION: UUID = UUID.fromString("32c7720d-440a-47a8-a866-a8b6bea9eeb6")

    // §2.2, §4.6 Current Time Service, hosted by the phone; the meter reads its clock from it.

    /** Current Time Service 0x1805, served by the phone (§2.2, §4.6). */
    public val CURRENT_TIME_SERVICE: UUID = sig(0x1805)

    /** Current Time 0x2A2B: 10 bytes, READ + NOTIFY (§4.6); see [CurrentTimeService]. */
    public val CURRENT_TIME: UUID = sig(0x2A2B)

    /** Local Time Information 0x2A0F: 2 bytes, READ (§4.6); see [CurrentTimeService]. */
    public val LOCAL_TIME_INFORMATION: UUID = sig(0x2A0F)

    /** Client Characteristic Configuration descriptor 0x2902 (§4.4). */
    public val CLIENT_CHARACTERISTIC_CONFIG: UUID = sig(0x2902)

    /** Advertised name prefix of the meter, e.g. "HONDA BTU 2040"; RoadSync matches with `startsWith` (§1.1). */
    public const val DEVICE_NAME_PREFIX: String = "HONDA BTU"

    /** The name pattern RoadSync gives the companion-device chooser (§1.1). */
    public const val DEVICE_NAME_PATTERN: String = "HONDA BTU [A-Za-z0-9]*"

    /** A standard 16-bit Bluetooth SIG UUID on the base `0000xxxx-0000-1000-8000-00805F9B34FB` (§2). */
    private fun sig(shortUuid: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(shortUuid))
}

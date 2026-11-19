package com.abid.handlebarlink.carplay.engine.session

import com.abid.handlebarlink.carplay.protocol.plist.Plist

/**
 * The HID input devices `/info` declares (DiPlay AirPlayHid.kt:22-134, AirPlayInfoPlist.kt:58-63; spec D §4): a
 * two-finger touchscreen whose logical maximum is the canvas size, a knob, media keys and telephony keys, all bound
 * to the main display. DiPlay notes the iPhone aborts a session whose `/info` lacks them (AirPlayInfoPlist.kt:3-8).
 * Reports go to the iPhone on the event channel as `hidSendReport` with the device's `uuid` ([touchCommand]).
 */
internal object HidDevices {
    const val TOUCH_UID = 0x2a2a2a2a
    const val KNOB_UID = 0x2a2a2a2b
    const val MEDIA_UID = 0x2a2a2a2c
    const val TELEPHONY_UID = 0x2a2a2a2d

    /** Touch contacts per report (AirPlayHid.kt:28). */
    const val TOUCH_CONTACTS = 2

    /** Bytes per finger in a touch report: slot, down, X and Y little-endian (AirPlayHid.kt:29, 44-55). */
    private const val BYTES_PER_FINGER = 6
    private const val DOWN_OFFSET = 1
    private const val X_OFFSET = 2
    private const val Y_OFFSET = 4

    /** One finger in canvas pixels, in report slot [slot] (DiPlay AirPlayContact after its scaling). */
    class Contact(
        val slot: Int,
        val x: Double,
        val y: Double,
        val down: Boolean,
    )

    /**
     * The touchscreen report (DiPlay AirPlayHid.touchReport, AirPlayHid.kt:44-55): per slot its number, 1 when the
     * finger is down, then X and Y rounded half up (`Math.round`) as u16 little-endian. A slot without a contact
     * holds only its number (an up at 0,0).
     */
    fun touchReport(contacts: List<Contact>): ByteArray {
        val report = ByteArray(BYTES_PER_FINGER * TOUCH_CONTACTS)
        for (slot in 0 until TOUCH_CONTACTS) {
            val offset = slot * BYTES_PER_FINGER
            report[offset] = slot.toByte()
            val contact = contacts.firstOrNull { it.slot == slot } ?: continue
            report[offset + DOWN_OFFSET] = if (contact.down) 1 else 0
            writeU16Le(report, offset + X_OFFSET, Math.round(contact.x.coerceAtLeast(0.0)).toInt())
            writeU16Le(report, offset + Y_OFFSET, Math.round(contact.y.coerceAtLeast(0.0)).toInt())
        }
        return report
    }

    /** `{type: hidSendReport, uuid: "2a2a2a2a", hidReport}`, keys in DiPlay's order (AirPlaySession.kt:444-451). */
    fun touchCommand(report: ByteArray): Plist.Dict =
        Plist.dict("type" to "hidSendReport", "uuid" to TOUCH_UID.toString(HEX), "hidReport" to report)

    fun all(
        name: String,
        width: Int,
        height: Int,
        displayUuid: String,
    ): List<Plist> =
        listOf(
            device(TOUCH_UID, "$name Touchscreen", touchDescriptor(width, height), displayUuid),
            device(KNOB_UID, "$name Knob", KNOB, displayUuid),
            device(MEDIA_UID, "$name Media", MEDIA, displayUuid),
            device(TELEPHONY_UID, "$name Telephony", TELEPHONY, displayUuid),
        )

    /** AirPlayHid.kt:74-87; the 1/2/0 ids are DiPlay's, Long so they are written as integers of minimal width. */
    private fun device(
        uid: Int,
        name: String,
        descriptor: ByteArray,
        displayUuid: String,
    ): Plist =
        Plist.dict(
            "hidProductID" to 1L,
            "hidVendorID" to 2L,
            "hidCountryCode" to 0L,
            "uuid" to uid.toString(HEX),
            "name" to name,
            "displayUUID" to displayUuid,
            "hidDescriptor" to descriptor,
        )

    /** Digitizer touch screen with [TOUCH_CONTACTS] fingers (AirPlayHid.kt:89-104); X and Y maxima little-endian. */
    fun touchDescriptor(
        xMax: Int,
        yMax: Int,
    ): ByteArray {
        val finger =
            hex("05 0d 09 22 a1 02  09 38 75 08 95 01 81 02  15 00 25 01 09 33 75 01 95 01 81 02  95 07 81 03") +
                hex("05 01 26") + u16Le(xMax) + hex("09 30 75 10 95 01 81 02") +
                hex("26") + u16Le(yMax) + hex("09 31 81 02 c0")
        var descriptor = hex("05 0d 09 04 a1 01")
        repeat(TOUCH_CONTACTS) { descriptor += finger }
        return descriptor + hex("c0")
    }

    /** Knob: select, home, back, X/Y and a relative wheel (AirPlayHid.kt:106-115). */
    private val KNOB =
        hex(
            "05 01 09 08 a1 01  05 09 09 01 15 00 25 01 75 01 95 01 81 02  05 0c 0a 23 02 0a 24 02 95 02 81 02 " +
                "95 05 81 01  05 01 09 01 a1 00  09 30 09 31 15 81 25 7f 75 08 95 02 81 02 c0 " +
                "09 38 15 81 25 7f 75 08 95 01 81 06 c0",
        )

    /** Consumer keys: play, pause, play/pause, next, previous, AC search (AirPlayHid.kt:117-123). */
    private val MEDIA =
        hex(
            "05 0c 09 01 a1 01  15 00 25 06 05 0c  0a 00 00 0a b0 00 0a b1 00 0a cd 00  0a b5 00 0a b6 00 0a 9e 02 " +
                "75 08 95 01 81 00 c0",
        )

    /** Telephony keys: hook, flash, redial, keypad, … (AirPlayHid.kt:125-133). */
    private val TELEPHONY =
        hex(
            "05 0b 09 07 a1 01  15 00 25 11 05 0b  09 00 09 20 09 21 09 26 09 2f " +
                "09 b0 09 b1 09 b2 09 b3 09 b4 09 b5  09 b6 09 b7 09 b8 09 b9 09 ba 09 bb  05 07 09 2a " +
                "75 08 95 01 81 00 c0",
        )

    private const val HEX = 16
    private const val BYTE_BITS = 8

    private fun writeU16Le(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr BYTE_BITS).toByte()
    }

    private fun u16Le(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr BYTE_BITS).toByte())

    private fun hex(text: String): ByteArray =
        text
            .filterNot { it == ' ' }
            .chunked(2)
            .map { it.toInt(HEX).toByte() }
            .toByteArray()
}

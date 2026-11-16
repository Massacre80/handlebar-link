package com.abid.handlebarlink.bike.protocol

/**
 * The Series Code characteristic: a uint16 sent little-endian, written as 4 hex digits with the
 * high byte first, e.g. bytes `F5 03` → "03F5" (§1.2).
 */
public data class SeriesCode(
    /** The 16-bit value. */
    val value: Int,
) {
    /** As RoadSync writes it: "03F5". */
    val hex: String get() = "%04X".format(value)

    /** Whether this is a CB350 (H'ness K0ZA and CB350RS K0ZF both report 03F5; §1.2). */
    val isCb350: Boolean get() = value == CB350

    /** Decoding. */
    public companion object {
        private const val CB350 = 0x03F5

        /** Decodes the characteristic value; null unless it is exactly 2 bytes. */
        public fun decode(value: ByteArray): SeriesCode? =
            if (value.size !=
                2
            ) {
                null
            } else {
                SeriesCode((value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8))
            }
    }
}

/**
 * The Display Capability bitmap (§2.1): what the meter can show. The CB350 reports
 * [indicator] only (value 1, verified on the bike).
 */
public data class DisplayCapability(
    /** Bit 0: the small indicator icons ([DataCommands.activeIndicators]). */
    val indicator: Boolean,
    /** Bit 1: pop-up screens. */
    val popUp: Boolean,
    /** Bit 2: full-screen layouts. */
    val mysteryBox: Boolean,
) {
    /** Decoding. */
    public companion object {
        /** Decodes the first byte; null for an empty value. */
        public fun decode(value: ByteArray): DisplayCapability? {
            val bits = value.firstOrNull()?.toInt() ?: return null
            return DisplayCapability(
                indicator = bits and 0x01 != 0,
                popUp = bits and 0x02 != 0,
                mysteryBox = bits and 0x04 != 0,
            )
        }
    }
}

/** The meter's firmware version, from [SabUuids.DFU_FIRMWARE_VERSION]: `[major, minor]` → "MM.mm" (§2.1). */
public data class FirmwareVersion(
    /** Major version. */
    val major: Int,
    /** Minor version. */
    val minor: Int,
) {
    override fun toString(): String = "%02d.%02d".format(major, minor)

    /** Decoding. */
    public companion object {
        /** Decodes the characteristic value; null unless it is exactly 2 bytes. */
        public fun decode(value: ByteArray): FirmwareVersion? =
            if (value.size != 2) null else FirmwareVersion(value[0].toInt() and 0xFF, value[1].toInt() and 0xFF)
    }
}

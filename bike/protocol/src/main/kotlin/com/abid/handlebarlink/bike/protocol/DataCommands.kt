package com.abid.handlebarlink.bike.protocol

/** Data command ids, the first byte of a command and of its receipt (§6.2). */
public enum class DataCommandId(
    /** The byte on the wire. */
    public val code: Int,
) {
    /** Full-screen layouts; needs [DisplayCapability.mysteryBox]. Not built by this module. */
    MYSTERY_BOX(0x01),

    /** Pop-up layouts; needs [DisplayCapability.popUp]. Not built by this module. */
    POP_UP(0x02),

    /** The small icons on the meter; needs [DisplayCapability.indicator] (§6.3). */
    ACTIVE_INDICATORS(0x03),

    /** Asks the meter to forward the switch to the phone (§5.4). */
    REQUEST_SAB_MODE(0x0A),

    /** A firmware-update notice. Never sent: [SabSafety] blocks it (§8). Known only to parse receipts. */
    FIRMWARE_UPDATE(0x0B),
    ;

    internal companion object {
        fun fromCode(code: Int): DataCommandId? = entries.firstOrNull { it.code == code }
    }
}

/** An icon on the CB350's meter, set with [DataCommands.activeIndicators] (§6.3). */
public enum class Indicator(
    /** The bit in the indicator byte. */
    public val bit: Int,
) {
    /** Phone. */
    PHONE(0x01),

    /** Navigation. */
    NAVIGATION(0x02),

    /** Music. */
    MUSIC(0x04),

    /** Message. */
    MESSAGE(0x08),

    /** Mute. */
    MUTE(0x10),

    /** No network. */
    NO_NETWORK(0x20),
}

/**
 * One data command. Write [frame] to [SabUuids.DATA_CONTROL_POINT], then wait for the receipt with
 * [DataCommands.match].
 */
public class DataCommand internal constructor(
    /** The request id carried in byte 1. */
    public val requestId: Int,
    /** The command. */
    public val id: DataCommandId,
    /** The bytes to write. */
    public val frame: SabFrame,
) {
    override fun toString(): String = "DataCommand($id, id=$requestId, $frame)"
}

/** A parsed receipt: `[command, requestId, status, (errorPosition)]` (§6.1). */
public data class Receipt(
    /** The command the receipt answers. */
    val command: DataCommandId,
    /** The request id the receipt answers. */
    val requestId: Int,
    /** The status byte; see [STATUS_OK]. RoadSync counts any status as delivered and only logs it. */
    val status: Int,
    /** Which parameter was wrong, if the meter said. */
    val errorPosition: Int?,
) {
    /** Whether the meter reported success. */
    val isOk: Boolean get() = status == STATUS_OK

    /** Receipt status values (§6.1). */
    public companion object {
        /** 0x01: done. */
        public const val STATUS_OK: Int = 0x01

        /** 0x0B: the meter does not know the command. */
        public const val STATUS_INVALID_COMMAND: Int = 0x0B

        /** 0x0C: invalid parameters. */
        public const val STATUS_INVALID_PARAMETERS: Int = 0x0C
    }
}

/** Builds the data commands the CB350 understands and matches its receipts (§5.4, §6). */
public object DataCommands {
    /** `0A rr`: asks the meter to enter phone mode, so presses reach the phone (§5.4). */
    public fun requestSabMode(ids: CommandRequestIds): DataCommand {
        val id = ids.next()
        return DataCommand(id, DataCommandId.REQUEST_SAB_MODE, frameOf(DataCommandId.REQUEST_SAB_MODE.code, id))
    }

    /**
     * `03 rr HH LL`: shows exactly [indicators] on the meter; an empty set clears them (§6.3).
     * `HH` is 0xFF when `LL` is negative as a signed byte, which never happens with these bits.
     */
    public fun activeIndicators(
        ids: CommandRequestIds,
        indicators: Set<Indicator>,
    ): DataCommand {
        val id = ids.next()
        val bits = indicators.fold(0) { acc, indicator -> acc or indicator.bit }
        val high = if (bits.toByte() < 0) 0xFF else 0x00
        return DataCommand(
            id,
            DataCommandId.ACTIVE_INDICATORS,
            frameOf(DataCommandId.ACTIVE_INDICATORS.code, id, high, bits),
        )
    }

    /**
     * Classifies a notification from [SabUuids.DATA_CONTROL_POINT] against the [command] just
     * written. RoadSync's rule: the request id matches (byte 1) and the bytes differ from the command.
     */
    public fun match(
        command: DataCommand,
        notification: ByteArray,
    ): ReplyMatch<Receipt> =
        when {
            notification.size < REQUEST_ID_END -> ReplyMatch.Malformed("too short: ${notification.toSabHex()}")
            notification[1] != command.frame[1] -> ReplyMatch.OtherRequest
            command.frame.contentEquals(notification) -> ReplyMatch.Echo
            else -> parse(notification)
        }

    private fun parse(value: ByteArray): ReplyMatch<Receipt> {
        val command = value.firstOrNull()?.let { DataCommandId.fromCode(it.toInt() and 0xFF) }
        return when {
            value.size !in RECEIPT_SIZES -> {
                ReplyMatch.Malformed("unexpected size ${value.size}: ${value.toSabHex()}")
            }

            command == null -> {
                ReplyMatch.Malformed("unknown command: ${value.toSabHex()}")
            }

            else -> {
                ReplyMatch.Matched(
                    Receipt(
                        command = command,
                        requestId = value[1].toInt() and 0xFF,
                        status = value[2].toInt() and 0xFF,
                        errorPosition = value.getOrNull(3)?.let { it.toInt() and 0xFF },
                    ),
                )
            }
        }
    }
}

private const val REQUEST_ID_END = 2
private val RECEIPT_SIZES = 3..4

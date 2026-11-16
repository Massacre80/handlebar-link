package com.abid.handlebarlink.bike.protocol

/**
 * One assignment request: asks the meter to send [function] to the phone (§5.2).
 * Write [frame] to [SabUuids.ASSIGNMENT_CONTROL], then wait for the matching result with
 * [Assignments.match].
 */
public class Assignment internal constructor(
    /** The request id carried in bytes 1–2. */
    public val requestId: Int,
    /** The function being registered. */
    public val function: FunctionId,
    /** The bytes to write. */
    public val frame: SabFrame,
) {
    override fun toString(): String = "Assignment($function, id=$requestId, $frame)"
}

/** Status byte of an assignment result (§5.2; RoadSync `Assignments.Status`). */
public enum class AssignmentStatus(
    /** The byte on the wire. */
    public val code: Int,
    /** Whether the registration counts as done. RoadSync fails the session on anything else. */
    public val isAccepted: Boolean,
) {
    /** 0x00: accepted by RoadSync's parser but not handled; treated as a failure at the end. */
    NOT_HANDLED(0x00, isAccepted = false),

    /** 0x01: registered. */
    OK(0x01, isAccepted = true),

    /** 0x0B: invalid id. */
    INVALID_ID(0x0B, isAccepted = false),

    /** 0x0C: invalid parameters (the result then carries an error position). */
    INVALID_PARAMETERS(0x0C, isAccepted = false),

    /** 0x0D: already registered, e.g. after a reconnect; counts as done. */
    ALREADY_REGISTERED(0x0D, isAccepted = true),
    ;

    internal companion object {
        fun fromCode(code: Int): AssignmentStatus? = entries.firstOrNull { it.code == code }
    }
}

/** A parsed assignment result: `[command, idLo, idHi, status, (errorPosition)]` (§5.2). */
public data class AssignmentResult(
    /** The command byte: 1 function, 2 colour, 3 end. */
    val command: Int,
    /** The request id the result answers. */
    val requestId: Int,
    /** The outcome. */
    val status: AssignmentStatus,
    /** For [AssignmentStatus.INVALID_PARAMETERS]: which parameter was wrong, if the meter said. */
    val errorPosition: Int?,
)

/** Builds the assignment requests the CB350 needs and matches the meter's results (§5.2). */
public object Assignments {
    /** Written after every result has arrived: a single byte, no request id (§5.2). */
    public val END: SabFrame = frameOf(COMMAND_END)

    /**
     * The 10 assignments RoadSync registers on a CB350, in its order (§5.2). The CB350 registers no
     * colours (`colorRegistrationNeeded` is false for K0ZA).
     */
    public fun forCb350(ids: AssignmentRequestIds): List<Assignment> =
        buildList {
            add(hidKey(ids.next(), FunctionId.VOLUME_UP, HID_USAGE_VOLUME_UP))
            add(hidKey(ids.next(), FunctionId.VOLUME_DOWN, HID_USAGE_VOLUME_DOWN))
            listOf(
                FunctionId.PREVIOUS,
                FunctionId.NEXT,
                FunctionId.BACK,
                FunctionId.SELECT,
                FunctionId.VOLUME_MUTE,
                FunctionId.MENU,
                FunctionId.MODE_SAB,
                FunctionId.MODE_HU,
            ).forEach { add(notifying(ids.next(), it)) }
        }

    /**
     * Classifies a notification from [SabUuids.ASSIGNMENT_CONTROL] against the [request] just
     * written. RoadSync's rule: the request id matches (bytes 1–2) and the bytes differ from the
     * request.
     */
    public fun match(
        request: Assignment,
        notification: ByteArray,
    ): ReplyMatch<AssignmentResult> =
        when {
            notification.size < REQUEST_ID_END -> ReplyMatch.Malformed("too short: ${notification.toSabHex()}")
            notification[1] != request.frame[1] || notification[2] != request.frame[2] -> ReplyMatch.OtherRequest
            request.frame.contentEquals(notification) -> ReplyMatch.Echo
            else -> parse(notification)
        }

    private fun parse(value: ByteArray): ReplyMatch<AssignmentResult> {
        if (value.size !in
            RESULT_SIZES
        ) {
            return ReplyMatch.Malformed("unexpected size ${value.size}: ${value.toSabHex()}")
        }
        val command = value[0].toInt() and 0xFF
        val status = AssignmentStatus.fromCode(value[3].toInt() and 0xFF)
        return when {
            command !in VALID_COMMANDS -> {
                ReplyMatch.Malformed("unknown command $command: ${value.toSabHex()}")
            }

            status == null -> {
                ReplyMatch.Malformed("unknown status: ${value.toSabHex()}")
            }

            else -> {
                ReplyMatch.Matched(
                    AssignmentResult(
                        command = command,
                        requestId = (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8),
                        status = status,
                        errorPosition = value.getOrNull(4)?.let { it.toInt() and 0xFF },
                    ),
                )
            }
        }
    }

    /** `01 idLo idHi fid 02`: the meter sends [function] on the Function Notification Source. */
    private fun notifying(
        requestId: Int,
        function: FunctionId,
    ) = Assignment(
        requestId,
        function,
        frameOf(COMMAND_FUNCTION, requestId and 0xFF, requestId shr 8, function.code, EVENT_NOTIFY),
    )

    /** `01 idLo idHi fid 01 07 usageLo usageHi`: the meter sends [function] as a HID keyboard key. */
    private fun hidKey(
        requestId: Int,
        function: FunctionId,
        usage: Int,
    ) = Assignment(
        requestId,
        function,
        frameOf(
            COMMAND_FUNCTION,
            requestId and 0xFF,
            requestId shr 8,
            function.code,
            EVENT_HID,
            HID_PAGE_KEYBOARD,
            usage and 0xFF,
            usage shr 8,
        ),
    )
}

private const val COMMAND_FUNCTION = 0x01
private const val COMMAND_END = 0x03
private val VALID_COMMANDS = 0x01..0x03
private const val EVENT_HID = 0x01
private const val EVENT_NOTIFY = 0x02
private const val HID_PAGE_KEYBOARD = 0x07
private const val HID_USAGE_VOLUME_UP = 0x0080
private const val HID_USAGE_VOLUME_DOWN = 0x0081
private const val REQUEST_ID_END = 3
private val RESULT_SIZES = 4..5

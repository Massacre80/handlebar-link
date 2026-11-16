package com.abid.handlebarlink.bike.protocol

/**
 * How a notification relates to a request the phone just wrote (§5.2, §6.1). The meter answers
 * on the same characteristic it was written to, so every notification is classified before use.
 */
public sealed interface ReplyMatch<out T> {
    /** The answer to the request. */
    public data class Matched<T>(
        /** The parsed answer. */
        val reply: T,
    ) : ReplyMatch<T>

    /** The notification repeats the request's own bytes (RoadSync logs it as "Corruption"); ignore it. */
    public data object Echo : ReplyMatch<Nothing>

    /** An answer to a different request id (RoadSync logs it as "Unsync"); ignore it. */
    public data object OtherRequest : ReplyMatch<Nothing>

    /** It carries the request's id but cannot be parsed; [reason] says why. */
    public data class Malformed(
        /** Why the notification was rejected, for the log. */
        val reason: String,
    ) : ReplyMatch<Nothing>
}

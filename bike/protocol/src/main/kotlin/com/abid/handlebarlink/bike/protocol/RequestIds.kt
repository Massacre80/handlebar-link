package com.abid.handlebarlink.bike.protocol

/**
 * Request ids for assignments: a uint16, incremented *before* use, so the first id is 1. After
 * 65535 it wraps to 0 (RoadSync `assignment/RequestIdGenerator`: `id = (id + 1) & 0xFFFF`).
 * On the wire it is little-endian (§5.2).
 *
 * One instance per meter session; not thread-safe.
 */
public class AssignmentRequestIds(
    /** The id the counter starts after; the first [next] returns `start + 1`. */
    start: Int = 0,
) {
    private var last: Int = start and MAX

    /** The next request id. */
    public fun next(): Int {
        last = (last + 1) and MAX
        return last
    }

    private companion object {
        const val MAX = 0xFFFF
    }
}

/**
 * Request ids for data commands: a uint8, used *before* incrementing, so the first id is 0, and it
 * wraps from 255 to 0 (RoadSync `datacommunication/commands/RequestIdGenerator`, §6.1).
 *
 * One instance per meter session; not thread-safe.
 */
public class CommandRequestIds(
    /** The first id [next] returns. */
    start: Int = 0,
) {
    private var nextId: Int = start and MAX

    /** The next request id. */
    public fun next(): Int {
        val id = nextId
        nextId = (nextId + 1) and MAX
        return id
    }

    private companion object {
        const val MAX = 0xFF
    }
}

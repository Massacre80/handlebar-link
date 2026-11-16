package com.abid.handlebarlink.bike.link

/**
 * A typed failure inside a meter session (docs/research/01-architecture.md §3.1). Thrown by the GATT
 * layer and the session; the runtime turns it into a [LinkError] event and it never leaves the module.
 */
internal class LinkException(
    val error: LinkError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

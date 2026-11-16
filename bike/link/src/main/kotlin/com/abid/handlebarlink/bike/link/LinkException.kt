package com.abid.handlebarlink.bike.link

/**
 * A typed failure of a GATT operation or a setup step (docs/research/01-architecture.md §3.1). Thrown by
 * the platform adapter (`spi` implementations) and the session; the runtime turns it into a [LinkError]
 * event, so it never reaches [BikeLink]'s users.
 */
public class LinkException(
    /** What failed. */
    public val error: LinkError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

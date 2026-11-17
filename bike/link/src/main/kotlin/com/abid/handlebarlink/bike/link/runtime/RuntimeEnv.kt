package com.abid.handlebarlink.bike.link.runtime

import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlin.random.Random

/** The injected environment of a runtime: time, randomness (backoff jitter) and logging. */
internal class RuntimeEnv(
    val time: TimeProvider,
    val random: Random,
    val log: Logger,
)

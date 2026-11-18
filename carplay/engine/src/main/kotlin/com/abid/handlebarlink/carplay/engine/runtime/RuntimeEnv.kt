package com.abid.handlebarlink.carplay.engine.runtime

import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import java.security.SecureRandom
import kotlin.random.Random

/** The injected environment: time, randomness for backoff jitter, cryptographic randomness, logging. */
internal class RuntimeEnv(
    val time: TimeProvider,
    val random: Random,
    val secureRandom: SecureRandom,
    val log: Logger,
)

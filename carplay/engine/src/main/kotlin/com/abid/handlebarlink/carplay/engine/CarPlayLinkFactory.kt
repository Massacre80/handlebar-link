package com.abid.handlebarlink.carplay.engine

import com.abid.handlebarlink.carplay.engine.runtime.CarPlayRuntime
import com.abid.handlebarlink.carplay.engine.runtime.RuntimeEnv
import com.abid.handlebarlink.carplay.engine.spi.CarPlayPorts
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Creates a [CarPlayLink] on the given platform [ports]. [dispatcher] must be single-threaded: all receiver
 * state is confined to it. [random] jitters the retry pauses; keys come from the platform's SecureRandom.
 */
public fun createCarPlayLink(
    ports: CarPlayPorts,
    dispatcher: CoroutineDispatcher,
    time: TimeProvider,
    random: Random,
    logger: Logger,
    config: CarPlayConfig = CarPlayConfig(),
): CarPlayLink = CarPlayRuntime(ports, dispatcher, RuntimeEnv(time, random, SecureRandom(), logger), config)

package com.abid.handlebarlink.bike.link

import com.abid.handlebarlink.bike.link.runtime.LinkRuntime
import com.abid.handlebarlink.bike.link.spi.LinkPorts
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.random.Random

/**
 * Creates a [BikeLink] on the given platform [ports]. [dispatcher] must be single-threaded: all link
 * state is confined to it. The Android module wraps this with its own ports; tests pass fakes.
 */
public fun createBikeLink(
    ports: LinkPorts,
    dispatcher: CoroutineDispatcher,
    time: TimeProvider,
    random: Random,
    logger: Logger,
    config: BikeLinkConfig = BikeLinkConfig(),
): BikeLink = LinkRuntime(ports, dispatcher, time, random, logger, config)

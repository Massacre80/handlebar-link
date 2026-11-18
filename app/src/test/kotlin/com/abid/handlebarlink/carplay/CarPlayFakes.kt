package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** The beta switch in memory. */
class InMemoryCarPlaySettings(
    enabled: Boolean = false,
) : CarPlaySettings {
    override val enabled = MutableStateFlow(enabled)

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }
}

/**
 * The beta switch as DataStore gives it: its first value arrives only after [loadMillis] (a file read), so the
 * time before CarPlay knows whether it will run can be seen (docs/LESSONS.md 51, 70).
 */
class SlowCarPlaySettings(
    enabled: Boolean,
    private val loadMillis: Long,
) : CarPlaySettings {
    private val stored = MutableStateFlow(enabled)

    override val enabled: Flow<Boolean> =
        flow {
            delay(loadMillis)
            emitAll(stored)
        }

    override suspend fun setEnabled(enabled: Boolean) {
        stored.value = enabled
    }
}

/** CarPlay's permissions as the test sets them: [missing] is read on every check. */
class FakeCarPlayPermissions(
    var missing: List<String> = emptyList(),
) : CarPlayPermissions {
    override fun missing(): List<String> = missing
}

/**
 * A [CarPlayLink] whose [run] fails with [failure] after [afterMillis] of running, as a bug in the engine would;
 * commands and state come from [fake].
 */
class FailingCarPlayLink(
    private val failure: Exception,
    private val afterMillis: Long = 1_000,
    val fake: FakeCarPlayLink = FakeCarPlayLink(),
) : CarPlayLink by fake {
    override suspend fun run() {
        delay(afterMillis)
        throw failure
    }
}

/** A coordinator over [link] (null: no platform ports). */
fun carPlayCoordinator(
    link: CarPlayLink?,
    settings: CarPlaySettings,
    permissions: CarPlayPermissions,
    logger: Logger,
): CarPlayCoordinator = CarPlayCoordinator(settings, link, permissions, logger)

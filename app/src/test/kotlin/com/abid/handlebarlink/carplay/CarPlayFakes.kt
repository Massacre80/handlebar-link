package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** The beta switch and the CarPlay screen in memory. */
class InMemoryCarPlaySettings(
    enabled: Boolean = false,
    orientation: CarPlayOrientation = CarPlayOrientation.DEFAULT,
) : CarPlaySettings {
    override val enabled = MutableStateFlow(enabled)

    override val orientation = MutableStateFlow(orientation)

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override suspend fun setOrientation(orientation: CarPlayOrientation) {
        this.orientation.value = orientation
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

    private val storedOrientation = MutableStateFlow(CarPlayOrientation.DEFAULT)

    override val orientation: Flow<CarPlayOrientation> = storedOrientation

    override suspend fun setOrientation(orientation: CarPlayOrientation) {
        storedOrientation.value = orientation
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

/** The orientations the coordinator gave the canvas, in order. */
class RecordingCanvas : CanvasOrientation {
    val used = mutableListOf<CarPlayOrientation>()

    override fun use(orientation: CarPlayOrientation) {
        used += orientation
    }
}

/** A coordinator over [link] (null: no platform ports). */
fun carPlayCoordinator(
    link: CarPlayLink?,
    settings: CarPlaySettings,
    permissions: CarPlayPermissions,
    logger: Logger,
    canvas: CanvasOrientation = RecordingCanvas(),
): CarPlayCoordinator = CarPlayCoordinator(settings, link, permissions, canvas, logger)

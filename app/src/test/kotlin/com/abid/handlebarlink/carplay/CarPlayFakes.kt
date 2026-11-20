package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.test.FakeCarPlayLink
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.core.testing.TestTimeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope

/** The CarPlay screen in memory. */
class InMemoryCarPlaySettings(
    orientation: CarPlayOrientation = CarPlayOrientation.DEFAULT,
) : CarPlaySettings {
    override val orientation = MutableStateFlow(orientation)

    override suspend fun setOrientation(orientation: CarPlayOrientation) {
        this.orientation.value = orientation
    }
}

/**
 * The settings as DataStore gives them: the first value arrives only after [loadMillis] (a file read), so the time
 * before CarPlay knows whether it will run can be seen (docs/LESSONS.md 51, 64).
 */
class SlowCarPlaySettings(
    private val loadMillis: Long,
) : CarPlaySettings {
    private val stored = MutableStateFlow(CarPlayOrientation.DEFAULT)

    override val orientation: Flow<CarPlayOrientation> =
        flow {
            delay(loadMillis)
            emitAll(stored)
        }

    override suspend fun setOrientation(orientation: CarPlayOrientation) {
        stored.value = orientation
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

/** A coordinator over [link] (null: no platform ports), on the test's virtual clock. */
fun TestScope.carPlayCoordinator(
    link: CarPlayLink?,
    settings: CarPlaySettings,
    permissions: CarPlayPermissions,
    logger: Logger,
    canvas: CanvasOrientation = RecordingCanvas(),
    time: TimeProvider = TestTimeProvider(testScheduler),
): CarPlayCoordinator = CarPlayCoordinator(settings, link, permissions, canvas, time, logger)

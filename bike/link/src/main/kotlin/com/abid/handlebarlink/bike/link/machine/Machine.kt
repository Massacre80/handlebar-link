package com.abid.handlebarlink.bike.link.machine

import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.MeterInfo
import com.abid.handlebarlink.bike.link.SetupStep

/**
 * The link's internal state machine (docs/research/02-ble-connection.md §2). [reduce] is pure: no
 * Android, no threads, no clock reads. Time comes in as `now` (monotonic milliseconds), and
 * everything that touches the outside world goes out as an [Effect].
 *
 * Each connection attempt gets a new generation number; events from older generations are ignored.
 */
internal data class Machine(
    val mode: Mode = Mode.Stopped,
    val facts: Facts = Facts(),
    /** Whether the bike is believed to be in range (CDM presence, a live link, a start hint). */
    val present: Boolean = false,
    val nextGen: Int = 1,
    /** Setup failures in a row; sets the backoff. Reset by a stable ready link or a new presence cycle. */
    val failures: Int = 0,
    /** Times of immediate connect errors within [Timeouts.EARLY_ERROR_WINDOW_MS]. */
    val earlyErrors: List<Long> = emptyList(),
    /** The stack looks stuck: re-arms are spaced out and the rider was told once. */
    val stuck: Boolean = false,
    /** Missing-key events and authentication failures since the last good bond. */
    val authFailures: Int = 0,
) {
    /** The generation of the open connection, if any. */
    val openGen: Int? get() = mode.gen
}

/** What the system reports; kept so a start can check the prerequisites at once. */
internal data class Facts(
    val bluetoothOn: Boolean = false,
    val bonded: Boolean = false,
    val permissionGranted: Boolean = false,
)

/** Internal modes; the public [com.abid.handlebarlink.bike.link.LinkState] is a projection. */
internal sealed interface Mode {
    /** The open connection's generation, if this mode holds one. */
    val gen: Int? get() = null

    data object Stopped : Mode

    /** Not running; companion-device presence will start it. */
    data object Dormant : Mode

    /** Opening the clock server before the first connect. */
    data object Arming : Mode

    /** Direct (autoConnect = false) attempt [attempt], started at [startedAt]. */
    data class Direct(
        override val gen: Int,
        val attempt: Int,
        val startedAt: Long,
    ) : Mode

    /** Pause before direct attempt [attempt]. */
    data class DirectPause(
        val attempt: Int,
    ) : Mode

    /**
     * autoConnect pending since [armedAt]. [lingering]: the bike is gone, and the link goes dormant
     * when the linger timer fires.
     */
    data class Waiting(
        override val gen: Int,
        val armedAt: Long,
        val lingering: Boolean,
    ) : Mode

    /** Link up, setting up the meter. */
    data class Linked(
        override val gen: Int,
        val step: SetupStep,
    ) : Mode

    data class Ready(
        override val gen: Int,
        val meter: MeterInfo,
        val since: Long,
    ) : Mode

    /** A setup failed; [until] is when the next attempt starts. */
    data class Backoff(
        val error: LinkError,
        val attempt: Int,
        val until: Long,
    ) : Mode

    data object BluetoothOff : Mode

    data object BondBroken : Mode

    data object PermissionMissing : Mode
}

/** Everything that happens to the link. */
internal sealed interface Event {
    data class Start(
        val presentHint: Boolean,
    ) : Event

    data object Stop : Event

    data object PresenceAppeared : Event

    data object PresenceGone : Event

    data class Bluetooth(
        val on: Boolean,
    ) : Event

    data class Bond(
        val bonded: Boolean,
    ) : Event

    /** The stack reported that the meter's keys are missing (`ACTION_KEY_MISSING`). */
    data object KeyMissing : Event

    data class Permission(
        val granted: Boolean,
    ) : Event

    data class ServerOpened(
        val ok: Boolean,
    ) : Event

    data class Connected(
        val gen: Int,
    ) : Event

    data class Disconnected(
        val gen: Int,
        val status: Int,
    ) : Event

    data class SetupProgress(
        val gen: Int,
        val step: SetupStep,
    ) : Event

    data class SetupDone(
        val gen: Int,
        val meter: MeterInfo,
    ) : Event

    data class SetupFailed(
        val gen: Int,
        val error: LinkError,
    ) : Event

    /** The meter's GATT table changed (`onServiceChanged`). */
    data class ServiceChanged(
        val gen: Int,
    ) : Event

    data class TimerFired(
        val timer: Timer,
        val gen: Int? = null,
    ) : Event
}

/** One timer of each kind runs at a time; starting one replaces the running one. */
internal enum class Timer {
    CONNECT_TIMEOUT,
    DIRECT_RETRY,
    BACKOFF,
    LINGER,
    SETUP_WATCHDOG,
    READY_STABLE,
    SERVER_RETRY,
}

/** What the runtime must do. */
internal sealed interface Effect {
    data class Foreground(
        val on: Boolean,
    ) : Effect

    data object OpenServer : Effect

    data object CloseServer : Effect

    data class Connect(
        val gen: Int,
        val autoConnect: Boolean,
    ) : Effect

    /** Close generation [gen]: cancel its setup and button collection, disconnect, close. */
    data class Close(
        val gen: Int,
    ) : Effect

    /** Run the setup session on [gen] (again, after a service change). */
    data class RunSetup(
        val gen: Int,
    ) : Effect

    /** [jitter]: the runtime varies the delay by ±20 % (backoff only). */
    data class StartTimer(
        val timer: Timer,
        val delayMillis: Long,
        val gen: Int? = null,
        val jitter: Boolean = false,
    ) : Effect

    data class CancelTimer(
        val timer: Timer,
    ) : Effect

    /** Keep the CPU awake during setup (capped by the setup watchdog). */
    data class WakeLock(
        val on: Boolean,
    ) : Effect

    /** Show or clear the problem notification. */
    data class Problem(
        val problem: LinkProblem?,
    ) : Effect
}

/** The result of [reduce]. */
internal data class Transition(
    val machine: Machine,
    val effects: List<Effect>,
)

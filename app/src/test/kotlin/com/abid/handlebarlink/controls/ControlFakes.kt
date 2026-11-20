package com.abid.handlebarlink.controls

import com.abid.handlebarlink.bike.protocol.HandlebarSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * The phone's microphone mute in memory, global like Android's: it outlives a [CarPlayControls] (a process
 * death). [refuses] plays Android ignoring the change (read back unchanged); [onSetMuted] sees each request first.
 */
class FakeMicrophone(
    var canMuteNow: Boolean = true,
    var refuses: Boolean = false,
    private val onSetMuted: (Boolean) -> Unit = {},
) : Microphone {
    override val canMute: Boolean get() = canMuteNow

    /** The mute as Android holds it; a test can set it as another app would. */
    var mutedNow: Boolean = false
    override val isMuted: Boolean get() = mutedNow

    /** Every change asked for, in order. */
    val requests = mutableListOf<Boolean>()

    override fun setMuted(muted: Boolean) {
        onSetMuted(muted)
        requests += muted
        if (!refuses) mutedNow = muted
    }
}

/**
 * The "we muted the mic" flag in memory; it outlives a [CarPlayControls], as the DataStore file does.
 * [failReads] and [failWrites] play a DataStore that throws IOException (a full disk, a corrupt file).
 */
class FakeMicMuteRecord(
    var flag: Boolean = false,
    var failReads: Boolean = false,
    var failWrites: Boolean = false,
) : MicMuteRecord {
    override suspend fun mutedByUs(): Boolean {
        if (failReads) throw IOException("read failed")
        return flag
    }

    override suspend fun set(mutedByUs: Boolean) {
        if (failWrites) throw IOException("write failed")
        flag = mutedByUs
    }
}

/**
 * A [ControlTarget] that records what it is asked; while [connected] is false it does nothing, as our CarPlay while
 * not connected.
 */
class FakeControlTarget(
    var connected: Boolean = true,
) : ControlTarget {
    val performed = mutableListOf<ControlAction>()

    override fun perform(action: ControlAction): ControlResult {
        if (!connected) return ControlResult.NotDone(Unavailable.NO_CARPLAY_SESSION)
        performed += action
        return ControlResult.Done
    }
}

/** A [CallSituations] the test moves by hand. */
class FakeCallSituations(
    start: CallSituation = CallSituation.RIDING,
) : CallSituations {
    val situation = MutableStateFlow(start)
    override val current: StateFlow<CallSituation> = situation
}

/** A [HandlebarMapping] in memory, with the same rule as the stored one: a situation's own actions only. */
class InMemoryHandlebarMapping : HandlebarMapping {
    private val state = MutableStateFlow(HANDLEBAR_DEFAULTS)
    override val current: StateFlow<Map<CallSituation, Map<HandlebarSwitch, RiderAction>>> = state

    override suspend fun assign(
        situation: CallSituation,
        switch: HandlebarSwitch,
        action: RiderAction,
    ): Boolean {
        if (!situation.allows(action)) return false
        state.value = state.value + (situation to state.value.getValue(situation) + (switch to action))
        return true
    }

    override suspend fun reset() {
        state.value = HANDLEBAR_DEFAULTS
    }
}

/** Records what the banner was asked to show. */
class RecordingPressFeedback : PressFeedback {
    val done = mutableListOf<Pair<RiderAction, String?>>()
    val unavailable = mutableListOf<Pair<RiderAction, Unavailable>>()

    override fun done(
        action: RiderAction,
        note: String?,
    ) {
        done += action to note
    }

    override fun unavailable(
        action: RiderAction,
        reason: Unavailable,
    ) {
        unavailable += action to reason
    }
}

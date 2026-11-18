package com.abid.handlebarlink.controls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's microphone mute ([AndroidMicrophone]; a fake in tests). It is one switch for the whole phone,
 * so muting it also mutes CarLink's CarPlay call, which records from the phone (callaudio/VoipCallWatcher).
 */
interface Microphone {
    /** Whether this app may change the mute at all. */
    val canMute: Boolean

    /** Whether the microphone is muted now. */
    val isMuted: Boolean

    /** Mutes or unmutes; read [isMuted] back to know whether Android did it. */
    fun setMuted(muted: Boolean)
}

/**
 * [Microphone] through `AudioManager.setMicrophoneMute` / `isMicrophoneMute` (API 1; AudioManager reference).
 * The reference adds that the method "should only be used by applications that replace the platform-wide
 * management of audio settings or the main telephony application"; it lists no permission, but AudioService
 * (AOSP `AudioService.setMicrophoneMute`) needs MODIFY_AUDIO_SETTINGS (Manifest.permission: "Protection level:
 * normal", so granted at install), refuses to unmute only when the OP_MUTE_MICROPHONE app op is set (a device
 * policy), and has no API-level gate. It is not one of the volume, focus and playback calls that Android 17's
 * background audio hardening blocks (Android 17 "Background audio hardening"). The mute is global and outlives
 * the caller, so [CarPlayControls] unmutes it itself after the call.
 */
class AndroidMicrophone
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : Microphone {
        private val audio = context.getSystemService(AudioManager::class.java)

        override val canMute: Boolean
            get() =
                audio != null &&
                    context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED

        override val isMuted: Boolean get() = audio?.isMicrophoneMute == true

        override fun setMuted(muted: Boolean) {
            // Checked here, next to the call (docs/LESSONS.md 28); without it AudioService ignores the call.
            val allowed =
                context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED
            if (allowed) audio?.setMicrophoneMute(muted)
        }
    }

/**
 * Whether this app muted the phone's microphone, kept across process deaths ([DataStoreMicMuteRecord]; a fake in
 * tests). The mute is global and outlives the process, so only a record that also survives can undo it.
 */
interface MicMuteRecord {
    /** Whether the flag is set. */
    suspend fun mutedByUs(): Boolean

    /** Sets or clears the flag; returns once stored. */
    suspend fun set(mutedByUs: Boolean)
}

/** [MicMuteRecord] in the app's DataStore (written to disk before [set] returns). */
class DataStoreMicMuteRecord
    @Inject
    constructor(
        private val store: DataStore<Preferences>,
    ) : MicMuteRecord {
        override suspend fun mutedByUs(): Boolean = store.data.map { it[MUTED_BY_US] == true }.first()

        override suspend fun set(mutedByUs: Boolean) {
            store.edit { if (mutedByUs) it[MUTED_BY_US] = true else it.remove(MUTED_BY_US) }
        }

        private companion object {
            val MUTED_BY_US = booleanPreferencesKey("mic_muted_by_app")
        }
    }

/**
 * What a press asks of CarPlay and the call: the music keys, answer, decline, end and Siri through
 * [ControlTarget] (our own CarPlay; CarLink takes only the music keys and returns
 * [Unavailable.NEEDS_OUR_CARPLAY] for the rest), and the microphone mute.
 *
 * Safety: the microphone mutes only during a call ([CallSituation.ON_CALL]). Before muting, the [MicMuteRecord]
 * flag is stored; it is cleared once unmuted. If this app muted it, it is unmuted as soon as the situation leaves
 * [CallSituation.ON_CALL] (`control.mic.unmuted_after_call`). The mute is global and outlives the process, so
 * [start] also undoes it after a restart: flag set and no call, it unmutes (`control.mic.unmuted_after_restart`);
 * flag set mid-call, it stays muted until that call ends. A microphone this app did not mute is never touched.
 */
@Singleton
class CarPlayControls
    @Inject
    constructor(
        private val target: ControlTarget,
        private val microphone: Microphone,
        private val situations: CallSituations,
        private val record: MicMuteRecord,
        @ApplicationScope private val scope: CoroutineScope,
        logger: Logger,
    ) {
        private val log = logger.child("control")
        private val lock = Mutex()
        private var mutedByUs = false

        /**
         * Watches the situation for the process's lifetime (HandlebarLinkApp, at every process start): first undoes
         * a mute a dead process left, then unmutes whenever a call this app muted ends. Call once.
         */
        fun start() {
            scope.launch {
                unmuteAfterRestart()
                situations.current.collect { situation ->
                    if (situation != CallSituation.ON_CALL) unmuteAfterCall(situation)
                }
            }
        }

        /** Whether the microphone is muted now (for the banner's note after [RiderAction.MUTE_MIC]). */
        val micMuted: Boolean get() = microphone.isMuted

        /**
         * Does an action that goes to CarPlay ([CONTROLS]: music, calls, Siri) or [RiderAction.MUTE_MIC]; any other
         * action is not one of these (a programming error).
         */
        suspend fun perform(action: RiderAction): ControlResult {
            if (action == RiderAction.MUTE_MIC) return toggleMicrophone()
            val control = CONTROLS[action] ?: error("$action does not go to CarPlay")
            return target.perform(control)
        }

        private suspend fun toggleMicrophone(): ControlResult =
            lock.withLock {
                // Read inside the lock: a call that ends after this read is seen by the collector, which waits
                // for the lock and then unmutes.
                when {
                    situations.current.value != CallSituation.ON_CALL -> ControlResult.NotDone(Unavailable.NO_CALL)
                    !microphone.canMute -> ControlResult.NotDone(Unavailable.MIC_REFUSED)
                    else -> changeMute(!microphone.isMuted)
                }
            }

        /**
         * Asks for [mute] and reads it back; only a change Android made counts. The flag is stored before muting
         * (a death right after the mute must find it) and cleared once unmuted. Called holding [lock].
         */
        private suspend fun changeMute(mute: Boolean): ControlResult {
            // No flag, no mute: a mute nothing records could never be undone after a death.
            if (mute && !store(true)) return ControlResult.NotDone(Unavailable.MIC_REFUSED)
            microphone.setMuted(mute)
            val changed = microphone.isMuted == mute
            if (changed) {
                mutedByUs = mute
                log.info("control.mic", "muted" to mute)
            } else {
                log.warn("control.mic.refused", "mute" to mute)
            }
            // Cleared when unmuted, or when the mute never happened; kept while it is still muted.
            if (!microphone.isMuted) store(false)
            return if (changed) ControlResult.Done else ControlResult.NotDone(Unavailable.MIC_REFUSED)
        }

        private suspend fun unmuteAfterRestart() {
            lock.withLock {
                if (!flagged()) return
                val situation = situations.current.value
                if (situation == CallSituation.ON_CALL) {
                    // Restarted mid-call: keep the mute; the end of this call unmutes it (unmuteAfterCall).
                    mutedByUs = true
                    log.info("control.mic.muted_across_restart")
                    return
                }
                unmute("control.mic.unmuted_after_restart", situation)
            }
        }

        private suspend fun unmuteAfterCall(situation: CallSituation) {
            lock.withLock { if (mutedByUs) unmute("control.mic.unmuted_after_call", situation) }
        }

        /** Unmutes a microphone this app muted; the flag goes once it is unmuted. Called holding [lock]. */
        private suspend fun unmute(
            event: String,
            situation: CallSituation,
        ) {
            if (microphone.isMuted) microphone.setMuted(false)
            val muted = microphone.isMuted
            if (!muted) {
                mutedByUs = false
                store(false)
            }
            log.info(event, "situation" to situation, "muted" to muted)
        }

        /**
         * Whether the [MicMuteRecord] flag is set; false when it cannot be read: nothing then proves the mute is
         * ours, so it is left alone, and the watch in [start] goes on.
         */
        private suspend fun flagged(): Boolean =
            try {
                record.mutedByUs()
            } catch (e: IOException) {
                log.error("control.mic.record_failed", e)
                false
            }

        /**
         * Stores the [MicMuteRecord] flag; false when the store failed. A failure must not end the watch in
         * [start] (a later mute would then outlive its call), so it is logged here, never thrown.
         */
        private suspend fun store(mutedByUs: Boolean): Boolean =
            try {
                record.set(mutedByUs)
                true
            } catch (e: IOException) {
                log.error("control.mic.record_failed", e, "mutedByUs" to mutedByUs)
                false
            }
    }

/**
 * Which actions work with the CarPlay we have now, and which situations can be told apart: for the Handlebar
 * page, to show what works today and what needs our own CarPlay. Momentary problems (no music session yet,
 * notification access off) are not here: they come back from the press itself.
 */
class ActionAvailability
    @Inject
    constructor(
        private val target: ControlTarget,
        private val microphone: Microphone,
        private val situations: CallSituations,
    ) {
        /** Why [action] can never work now, or null when it can. */
        fun whyUnavailable(action: RiderAction): Unavailable? =
            when {
                action == RiderAction.MUTE_MIC -> if (microphone.canMute) null else Unavailable.MIC_REFUSED
                CONTROLS[action].let { it != null && it !in target.supported } -> Unavailable.NEEDS_OUR_CARPLAY
                else -> null
            }

        /** Why the [situation] layer is never used now (ringing needs our own CarPlay), or null when it is. */
        fun whyUnavailable(situation: CallSituation): Unavailable? =
            if (situation == CallSituation.RINGING && !situations.seesRinging) Unavailable.NEEDS_OUR_CARPLAY else null
    }

/** The call and Siri actions, and what each asks CarPlay to do. */
internal val CALL_CONTROLS: Map<RiderAction, ControlAction> =
    mapOf(
        RiderAction.ANSWER_CALL to ControlAction.ANSWER_CALL,
        RiderAction.DECLINE_CALL to ControlAction.DECLINE_CALL,
        RiderAction.END_CALL to ControlAction.END_CALL,
        RiderAction.SIRI to ControlAction.SIRI,
    )

/** The actions that drive CarPlay's music, and the media key each sends. */
internal val MEDIA_CONTROLS: Map<RiderAction, ControlAction> =
    mapOf(
        RiderAction.PLAY_PAUSE to ControlAction.PLAY_PAUSE,
        RiderAction.NEXT to ControlAction.NEXT,
        RiderAction.PREVIOUS to ControlAction.PREVIOUS,
    )

/** Every action that goes to the [ControlTarget]. */
internal val CONTROLS: Map<RiderAction, ControlAction> = MEDIA_CONTROLS + CALL_CONTROLS

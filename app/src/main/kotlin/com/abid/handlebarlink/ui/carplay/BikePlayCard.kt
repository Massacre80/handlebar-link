package com.abid.handlebarlink.ui.carplay

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.usedIphone
import com.abid.handlebarlink.core.designsystem.component.MeterTone
import com.abid.handlebarlink.core.designsystem.component.MeterValue
import com.abid.handlebarlink.ui.permissions.Access
import java.time.Duration
import java.time.Instant

// Home's BikePlay card, design B "Instrument" (Paper "BikePlay card", owner's choice 2026-11-20): the meter, its state
// word and one line are the whole status (never repeated by a tag or a second line); a button only where the rider
// must or can act. Built from the BikePlay tab's hero (carPlayHero) so the two never disagree.

/** The card's nine states (Paper "B · 1" … "B · 9"; the three Needs you share one). */
enum class CardPhase {
    /** B · 1: running; the link's quality and the Wi-Fi Direct band on the meter's base; Open BikePlay. */
    CONNECTED,

    /** B · 2: step 1 of 4, no button. */
    STARTING,

    /** B · 3: steps 2–4, no button. */
    CONNECTING,

    /** B · 4: no iPhone paired; Add iPhone. */
    NOT_SET_UP,

    /** B · 5–7: only the rider can fix it; the fix as the button. */
    NEEDS_YOU,

    /** B · 8: an attempt failed and a retry counts down; Try now. */
    RETRYING,

    /** B · 9: no ride running, or the rider stopped it; Start BikePlay. */
    OFF,
}

/** The card: the [word] in the meter, its [detail] line, the [meter] in its [tone], the readings and one [action]. */
@Immutable
data class BikePlayCardState(
    val phase: CardPhase,
    @param:StringRes val word: Int,
    val detail: HeroDetail,
    val meter: MeterValue,
    val tone: MeterTone,
    /** Connected: the link's quality (null until the picture has a window of numbers). */
    val quality: LinkQuality? = null,
    /** Connected: the Wi-Fi Direct channel (null when unknown). */
    val channel: Int? = null,
    val action: TabAction? = null,
)

/**
 * The card for BikePlay's [status], the paired [iphones] and the [chosen] one, the status [line] (the retry's words),
 * the link's steady [quality] ([SteadyLinkQuality]), whether Bluetooth is on ([bluetoothOn], as the tab) and
 * the time [now] (how much of a retry's pause is left).
 */
@Suppress("LongParameterList") // One input per part of the card, as carPlayTab.
internal fun bikePlayCard(
    status: CarPlayStatus,
    iphones: List<PairedIphone>?,
    chosen: String?,
    line: CarPlayLine,
    quality: LinkQuality?,
    bluetoothOn: Boolean,
    now: Instant,
): BikePlayCardState {
    val hero = carPlayHero(status, iphones, chosen, line, bluetoothOn)
    val state = (status as? CarPlayStatus.Engine)?.state
    return when (hero.phase) {
        TabPhase.NOT_SET_UP -> {
            BikePlayCardState(
                CardPhase.NOT_SET_UP,
                R.string.carplay_tag_not_set_up,
                HeroDetail.Text(R.string.bikeplay_card_not_set_up),
                MeterValue.Empty,
                MeterTone.GOOD,
                action = TabAction.ADD_IPHONE,
            )
        }

        TabPhase.CONNECTED -> {
            connected(
                iphones?.let { usedIphone(it, chosen) }?.name,
                quality,
                (state as? CarPlayState.Connected)?.wifiChannel,
            )
        }

        TabPhase.CONNECTING -> {
            if (state is CarPlayState.Recovering) retrying(state, line, now) else connecting(state)
        }

        TabPhase.NEEDS_YOU -> {
            BikePlayCardState(
                CardPhase.NEEDS_YOU,
                R.string.carplay_tag_needs_you,
                HeroDetail.Text(hero.title),
                MeterValue.Full,
                MeterTone.DANGER,
                action = hero.primary,
            )
        }

        TabPhase.READY, TabPhase.HELD -> {
            off(held = hero.phase == TabPhase.HELD)
        }
    }
}

/** Connected to the iPhone [name] (null: none named) over [channel], its link [quality]; Open BikePlay. */
private fun connected(
    name: String?,
    quality: LinkQuality?,
    channel: Int?,
) = BikePlayCardState(
    CardPhase.CONNECTED,
    R.string.carplay_status_connected,
    name?.let(HeroDetail::Name) ?: HeroDetail.Text(R.string.bikeplay_card_your_iphone),
    MeterValue.Segments(quality.segments()),
    MeterTone.GOOD,
    quality = quality,
    channel = channel,
    action = TabAction.OPEN_CARPLAY,
)

/** BikePlay off: with the next ride, or ([held]) until the rider starts it or the next ignition; Start BikePlay. */
private fun off(held: Boolean) =
    BikePlayCardState(
        CardPhase.OFF,
        R.string.bikeplay_card_off,
        HeroDetail.Text(if (held) R.string.bikeplay_card_held else R.string.bikeplay_card_starts_when_riding),
        MeterValue.Empty,
        MeterTone.GOOD,
        action = TabAction.START_CARPLAY,
    )

/** Starting (step 1 of 4) or Connecting (steps 2–4): the arc lit to the step, the step's words, no button. */
private fun connecting(state: CarPlayState?): BikePlayCardState {
    val step = (state as? CarPlayState.Connecting)?.step
    val number = step?.let { connectPhase(it).first } ?: 1
    return BikePlayCardState(
        if (number == 1) CardPhase.STARTING else CardPhase.CONNECTING,
        if (number == 1) R.string.carplay_status_starting else R.string.carplay_tag_connecting,
        HeroDetail.Text(cardStepWords(number)),
        MeterValue.Progress(number.toFloat() / CONNECT_PHASES),
        MeterTone.GOOD,
    )
}

/**
 * The card's words for step [number] of 4 (Paper "B · 2": "Calling your iPhone"; "B · 3", step 3: "Waiting for the
 * iPhone to join", where the tab says "Joining Wi-Fi").
 */
@StringRes
internal fun cardStepWords(number: Int): Int =
    when (number) {
        1 -> R.string.carplay_phase_calling
        2 -> R.string.carplay_phase_checking
        CONNECT_PHASES -> R.string.carplay_phase_securing
        else -> R.string.carplay_step_waiting
    }

/** Couldn't connect: what failed and the countdown ([line]), the danger arc draining with it, Try now. */
private fun retrying(
    state: CarPlayState.Recovering,
    line: CarPlayLine,
    now: Instant,
) = BikePlayCardState(
    CardPhase.RETRYING,
    R.string.bikeplay_card_couldnt_connect,
    HeroDetail.Line(line),
    MeterValue.Progress(pauseLeft(state, now), draining = true),
    MeterTone.DANGER,
    action = TabAction.RETRY_NOW,
)

/** How much of the retry's pause is still to wait at [now], 1 (just failed) to 0 (due); 0 without a pause. */
internal fun pauseLeft(
    state: CarPlayState.Recovering,
    now: Instant,
): Float {
    val whole = Duration.between(state.failedAt, state.retryAt).toMillis()
    if (whole <= 0) return 0f
    val left = Duration.between(now, state.retryAt).toMillis()
    return (left.toFloat() / whole).coerceIn(0f, 1f)
}

/** Lit segments for a link [quality]: Strong 3, Fair 2, Weak 1; all three before there is a verdict. */
internal fun LinkQuality?.segments(): Int =
    when (this) {
        LinkQuality.STRONG, null -> MeterValue.SEGMENTS
        LinkQuality.FAIR -> 2
        LinkQuality.WEAK -> 1
    }

/** The card's Allow as Android still allows it ([access]): Open settings once it no longer asks (as [withAccess]). */
fun BikePlayCardState.withAccess(access: Access): BikePlayCardState =
    if (action == TabAction.ALLOW && access == Access.BLOCKED) copy(action = TabAction.OPEN_SETTINGS) else this

/**
 * Whether [action] is the lime button: everything the rider must or can do now (Open, Add, Start, the fixes, Try now;
 * owner, 2026-11-20); only dismissing (Cancel, Disconnect) is a secondary button.
 */
internal fun primaryAction(action: TabAction): Boolean = action != TabAction.CANCEL && action != TabAction.DISCONNECT

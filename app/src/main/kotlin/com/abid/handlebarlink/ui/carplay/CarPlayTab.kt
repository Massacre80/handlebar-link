package com.abid.handlebarlink.ui.carplay

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.CarPlayStatus
import com.abid.handlebarlink.carplay.engine.CarPlayError
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import com.abid.handlebarlink.carplay.engine.android.video.CarPlayVideoStats
import com.abid.handlebarlink.carplay.usedIphone
import com.abid.handlebarlink.core.designsystem.component.SignalTone

/** The CarPlay tab's hero, after Paper "CarPlay — flow v2" (Tab 1–4) and the states it implies. */
enum class TabPhase {
    /** No paired iPhone yet: add one (Tab 1). */
    NOT_SET_UP,

    /** An iPhone, no ride running: CarPlay starts with the ride. */
    READY,

    /** Starting CarPlay, step by step (Tab 2). */
    CONNECTING,

    /** CarPlay is running (Tab 3). */
    CONNECTED,

    /** The rider stopped CarPlay (Cancel or Disconnect). */
    HELD,

    /** Only the rider can fix what stops it (Tab 4): the error and its one action. */
    NEEDS_YOU,
}

/** What a hero button does; the screen's entry turns each into a call or a system screen. */
enum class TabAction {
    ADD_IPHONE,
    CHOOSE_IPHONE,
    START_CARPLAY,
    OPEN_CARPLAY,
    CANCEL,
    DISCONNECT,

    /** Asks for CarPlay's missing permissions (Android may still ask). */
    ALLOW,

    /** Opens the app's permission settings: Android no longer asks for a permission refused for good. */
    OPEN_SETTINGS,
    TURN_ON_WIFI,
    TURN_ON_BLUETOOTH,
}

/** The hero's detail line. */
@Immutable
sealed interface HeroDetail {
    /** A fixed line. */
    data class Text(
        @param:StringRes val text: Int,
    ) : HeroDetail

    /** "Step [number] of 4 · [step]". */
    data class Step(
        val number: Int,
        @param:StringRes val step: Int,
    ) : HeroDetail

    /** CarPlay's status line (a retry counting down). */
    data class Line(
        val line: CarPlayLine,
    ) : HeroDetail

    /** A line naming the iPhone ([text] takes [name] as its argument). */
    data class Iphone(
        @param:StringRes val text: Int,
        val name: String,
    ) : HeroDetail

    /** The permissions still missing, named ("Missing: Nearby devices and Microphone"). */
    data class Missing(
        val grants: List<CarPlayGrant>,
    ) : HeroDetail
}

/** The detail's words. */
@Composable
fun HeroDetail.words(): String =
    when (this) {
        is HeroDetail.Text -> stringResource(text)
        is HeroDetail.Step -> stringResource(R.string.carplay_tab_step, number, CONNECT_PHASES, stringResource(step))
        is HeroDetail.Line -> line.words()
        is HeroDetail.Iphone -> stringResource(text, name)
        is HeroDetail.Missing -> stringResource(R.string.carplay_tab_access_detail, listWords(grants.map { it.label }))
    }

/** The hero: a dial ([tone], [progress]), a [tag], a big [title], a [detail], one [primary] and one [ghost] action. */
@Immutable
data class CarPlayHero(
    val phase: TabPhase,
    @param:StringRes val tag: Int,
    @param:StringRes val title: Int,
    val detail: HeroDetail,
    val tone: SignalTone,
    /** The dial's lit part; null lights it whole. */
    val progress: Float? = null,
    val primary: TabAction? = null,
    val ghost: TabAction? = null,
)

/** The status list's iPhone row: the iPhone CarPlay uses ([name] null: none yet or none chosen). */
@Immutable
data class IphoneRow(
    val name: String?,
    @param:StringRes val hint: Int,
    @param:StringRes val detail: Int,
    /** Some iPhone is paired: the row opens the iPhone sheet; otherwise pairing. */
    val hasIphones: Boolean,
)

/** The status list's Link row: what carries CarPlay now. */
@Immutable
data class LinkRow(
    @param:StringRes val hint: Int,
    /** The detail; it takes [channel] as its argument when that is known. */
    @param:StringRes val detail: Int,
    val live: Boolean,
    /** The Wi-Fi Direct channel while connected. */
    val channel: Int? = null,
    /** How well the picture comes through ([linkQuality]); null when there is nothing to judge yet. */
    val quality: LinkQuality? = null,
)

/** The Link row's quality word. */
enum class LinkQuality(
    @param:StringRes val words: Int,
) {
    STRONG(R.string.carplay_link_quality_strong),
    FAIR(R.string.carplay_link_quality_fair),
    WEAK(R.string.carplay_link_quality_weak),
}

/** The Link row's detail: "Wi-Fi Direct · 5 GHz ch 36 · Strong" while connected. */
@Composable
fun LinkRow.detailWords(): String {
    val base = if (channel != null) stringResource(detail, channel) else stringResource(detail)
    return quality?.let { stringResource(R.string.carplay_link_with_quality, base, stringResource(it.words)) } ?: base
}

/** The CarPlay tab. */
@Immutable
data class CarPlayUiState(
    val hero: CarPlayHero = carPlayHero(NOT_LOADED, null, null, NOT_LOADED_LINE),
    val iphone: IphoneRow = iphoneRow(NOT_LOADED, null, null),
    val link: LinkRow = linkRow(NOT_LOADED),
    /** The "CarPlay screen" row: which way the picture stands. */
    val orientation: CarPlayOrientation = CarPlayOrientation.DEFAULT,
)

/**
 * The tab for CarPlay's [status], the paired [iphones] (null: unknown), the [chosen] one, the status [line], the
 * picture's last window of numbers ([video], for the Link row's quality), the "CarPlay screen" setting
 * ([orientation]) and whether Bluetooth is on ([bluetoothOn], read only when the engine says Bluetooth is
 * unavailable).
 */
@Suppress("LongParameterList") // One input per part of the page.
fun carPlayTab(
    status: CarPlayStatus,
    iphones: List<PairedIphone>?,
    chosen: String?,
    line: CarPlayLine,
    video: CarPlayVideoStats? = null,
    orientation: CarPlayOrientation = CarPlayOrientation.DEFAULT,
    bluetoothOn: Boolean = true,
): CarPlayUiState =
    CarPlayUiState(
        hero = carPlayHero(status, iphones, chosen, line, bluetoothOn),
        iphone = iphoneRow(status, iphones, chosen),
        link = linkRow(status, video),
        orientation = orientation,
    )

/**
 * The hero, in priority order: no build support; no paired iPhone (Add iPhone); CarPlay starting or running (whatever
 * the app's own choice says: the engine may have found the iPhone itself, IphoneChoice, and Open CarPlay must stay);
 * paired iPhones but none CarPlay can use (two and none chosen, or the chosen one unpaired); then CarPlay's own status.
 * [bluetoothOn] picks the fix when the engine reports Bluetooth unavailable ([fixFor]).
 */
internal fun carPlayHero(
    status: CarPlayStatus,
    iphones: List<PairedIphone>?,
    chosen: String?,
    line: CarPlayLine,
    bluetoothOn: Boolean = true,
): CarPlayHero {
    val used = iphones?.let { usedIphone(it, chosen) }
    val state = (status as? CarPlayStatus.Engine)?.state
    // The engine is starting or running CarPlay now (its steps or Connected).
    val running = state is CarPlayState.Connected || state is CarPlayState.Connecting || state == CarPlayState.Idle
    return when {
        status == CarPlayStatus.NotAvailable -> {
            needsYou(R.string.carplay_status_not_available, R.string.carplay_tab_unavailable_detail, null)
        }

        iphones?.isEmpty() == true -> {
            CarPlayHero(
                TabPhase.NOT_SET_UP,
                R.string.carplay_tag_not_set_up,
                R.string.carplay_tab_add_title,
                HeroDetail.Text(R.string.carplay_tab_add_detail),
                SignalTone.IDLE,
                progress = 0f,
                primary = TabAction.ADD_IPHONE,
            )
        }

        running -> {
            statusHero(status, used?.name, line, bluetoothOn)
        }

        iphones != null && used == null -> {
            needsYou(R.string.carplay_tab_choose_title, R.string.carplay_tab_choose_detail, TabAction.CHOOSE_IPHONE)
        }

        else -> {
            statusHero(status, used?.name, line, bluetoothOn)
        }
    }
}

private fun statusHero(
    status: CarPlayStatus,
    iphone: String?,
    line: CarPlayLine,
    bluetoothOn: Boolean,
): CarPlayHero =
    when (status) {
        CarPlayStatus.NotAvailable -> {
            // Handled before; kept total for the compiler.
            needsYou(R.string.carplay_status_not_available, R.string.carplay_tab_unavailable_detail, null)
        }

        is CarPlayStatus.NeedsPermission -> {
            needsAccess(missingAccess(status.permissions))
        }

        // Start CarPlay starts the engine again (CarPlayCoordinator.start), in the running ride or a new one.
        CarPlayStatus.Failed -> {
            needsYou(R.string.carplay_tab_failed_title, R.string.carplay_status_failed, TabAction.START_CARPLAY)
        }

        CarPlayStatus.Held -> {
            CarPlayHero(
                TabPhase.HELD,
                R.string.carplay_tag_stopped,
                R.string.carplay_tab_held_title,
                iphoneDetail(R.string.carplay_tab_held_detail, iphone),
                SignalTone.IDLE,
                progress = 0f,
                primary = TabAction.START_CARPLAY,
            )
        }

        is CarPlayStatus.Engine -> {
            engineHero(status.state, iphone, line, bluetoothOn)
        }
    }

private fun engineHero(
    state: CarPlayState,
    iphone: String?,
    line: CarPlayLine,
    bluetoothOn: Boolean,
): CarPlayHero =
    when (state) {
        CarPlayState.Stopped -> {
            CarPlayHero(
                TabPhase.READY,
                R.string.carplay_tag_ready,
                R.string.carplay_tab_ready_title,
                iphoneDetail(R.string.carplay_tab_ready_detail, iphone),
                SignalTone.IDLE,
                progress = 0f,
                primary = TabAction.START_CARPLAY,
            )
        }

        CarPlayState.Idle -> {
            connecting(HeroDetail.Step(1, R.string.carplay_phase_calling), 1)
        }

        is CarPlayState.Connecting -> {
            val (number, words) = connectPhase(state.step)
            connecting(HeroDetail.Step(number, words), number)
        }

        is CarPlayState.Connected -> {
            CarPlayHero(
                TabPhase.CONNECTED,
                R.string.carplay_tag_connected,
                R.string.carplay_tab_connected_title,
                iphoneDetail(R.string.carplay_tab_connected_detail, iphone),
                SignalTone.ON,
                primary = TabAction.OPEN_CARPLAY,
                ghost = TabAction.DISCONNECT,
            )
        }

        is CarPlayState.Recovering -> {
            val error = state.error
            if (error is CarPlayError.PermissionMissing) {
                needsAccess(listOf(carPlayGrant(error.permission)))
            } else if (error.needsUser) {
                needsYou(
                    heroTitle(error, bluetoothOn),
                    R.string.carplay_tab_needs_you_detail,
                    fixFor(error, bluetoothOn),
                )
            } else {
                // Retrying by itself: still starting, with the countdown.
                connecting(HeroDetail.Line(line), 1)
            }
        }
    }

private fun connecting(
    detail: HeroDetail,
    number: Int,
) = CarPlayHero(
    TabPhase.CONNECTING,
    R.string.carplay_tag_connecting,
    R.string.carplay_tab_connecting_title,
    detail,
    SignalTone.PROGRESS,
    progress = number.toFloat() / CONNECT_PHASES,
    ghost = TabAction.CANCEL,
)

internal fun needsYou(
    @StringRes title: Int,
    @StringRes detail: Int,
    action: TabAction?,
) = needsYou(title, HeroDetail.Text(detail), action)

internal fun needsYou(
    @StringRes title: Int,
    detail: HeroDetail,
    action: TabAction?,
) = CarPlayHero(
    TabPhase.NEEDS_YOU,
    R.string.carplay_tag_needs_you,
    title,
    detail,
    SignalTone.ERROR,
    primary = action,
)

private fun iphoneDetail(
    @StringRes text: Int,
    iphone: String?,
): HeroDetail =
    if (iphone !=
        null
    ) {
        HeroDetail.Iphone(text, iphone)
    } else {
        HeroDetail.Text(R.string.carplay_tab_any_iphone)
    }

/** The rider's connection steps ("Step N of 4"). */
internal const val CONNECT_PHASES = 4

/** Before the first status arrives: no ride yet, nothing known (shown for a few milliseconds). */
private val NOT_LOADED: CarPlayStatus = CarPlayStatus.Engine(CarPlayState.Stopped)
private val NOT_LOADED_LINE = CarPlayLine(R.string.carplay_status_waiting, tone = SignalTone.IDLE)

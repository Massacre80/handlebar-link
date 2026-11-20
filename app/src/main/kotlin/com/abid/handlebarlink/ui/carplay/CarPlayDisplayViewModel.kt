package com.abid.handlebarlink.ui.carplay

import android.view.SurfaceView
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlayCanvas
import com.abid.handlebarlink.carplay.CarPlayDisplayHint
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.engine.CarPlayConfig
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayViewArea
import com.abid.handlebarlink.carplay.engine.TouchContact
import com.abid.handlebarlink.carplay.engine.android.CarPlayScreen
import com.abid.handlebarlink.carplay.orientation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The CarPlay display: whether the picture is live, the status line otherwise, and whether to show the hint. */
@Immutable
data class CarPlayDisplayUiState(
    /** The iPhone is connected: its picture shows. */
    val connected: Boolean = false,
    /** What CarPlay is doing, shown over the last picture while not connected. */
    val line: CarPlayLine? = null,
    /** The "Swipe back" hint has not been shown yet (null until known). */
    val hintDue: Boolean? = null,
)

/**
 * The shape of the picture on the display: the orientation the screen is locked to, and the canvas the iPhone draws on
 * with the area it draws in ([picture]; null before any attempt, when the view fills the window).
 */
@Immutable
data class DisplayShape(
    val orientation: CarPlayOrientation,
    val picture: PictureSpec?,
)

/** A canvas of [canvasWidth] x [canvasHeight] pixels, of which CarPlay draws in [area]. */
@Immutable
data class PictureSpec(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val area: CarPlayViewArea,
)

/**
 * The display's shape for the canvas the latest attempt [reported] (its start area: our sessions never move CarPlay to
 * another area), or, before any attempt, the orientation the next one will report ([chosen]) with no picture.
 */
internal fun displayShape(
    reported: CarPlayConfig?,
    chosen: CarPlayOrientation,
): DisplayShape =
    if (reported == null) {
        DisplayShape(chosen, null)
    } else {
        DisplayShape(
            reported.orientation,
            PictureSpec(reported.widthPixels, reported.heightPixels, reported.startViewArea),
        )
    }

/**
 * The CarPlay display's view model: shows the engine's picture ([CarPlayScreen]) and sends the rider's touches to the
 * engine ([CarPlayLink.touch]). Leaving the display only stops drawing to its view; CarPlay keeps running.
 */
@HiltViewModel
class CarPlayDisplayViewModel
    @Inject
    constructor(
        private val link: CarPlayLink?,
        private val screen: CarPlayScreen,
        private val hint: CarPlayDisplayHint,
        canvas: CarPlayCanvas,
        lines: CarPlayStatusLines,
    ) : ViewModel() {
        /**
         * The canvas's orientation (the display locks the screen to it), the canvas and the area CarPlay draws in, so
         * the display shows that area filling its window. Read from what the engine reported; this never measures (main
         * thread).
         */
        val shape: StateFlow<DisplayShape> =
            combine(canvas.reported, canvas.orientation, ::displayShape)
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    displayShape(canvas.reported.value, canvas.orientation.value),
                )

        /** The page's state. */
        val uiState: StateFlow<CarPlayDisplayUiState> =
            combine(
                // The steady status, as the line beside it: a drop of a moment flashes neither overlay nor text.
                lines.status.map { it.isConnected() },
                lines.lines,
                hint.shown,
            ) { connected, line, shown -> CarPlayDisplayUiState(connected, line, hintDue = !shown) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CarPlayDisplayUiState())

        /** Draws the picture into [view] until the handle is closed (main thread). */
        fun show(view: SurfaceView): AutoCloseable = screen.show(view)

        /** The fingers on the picture now. */
        fun touch(contacts: List<TouchContact>) {
            link?.touch(contacts)
        }

        /** The hint was shown; it is not shown again. */
        fun hintShown() {
            viewModelScope.launch { hint.markShown() }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

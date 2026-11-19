package com.abid.handlebarlink.ui.carplay

import android.view.SurfaceView
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abid.handlebarlink.carplay.CarPlayCanvas
import com.abid.handlebarlink.carplay.CarPlayDisplayHint
import com.abid.handlebarlink.carplay.engine.CarPlayLink
import com.abid.handlebarlink.carplay.engine.CarPlayState
import com.abid.handlebarlink.carplay.engine.TouchContact
import com.abid.handlebarlink.carplay.engine.android.CarPlayScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
         * The canvas's width over its height, so the picture keeps its shape on any window; null fills the window
         * (the canvas is the whole screen). The engine measures the screen at its first attempt; this never does
         * (main thread).
         */
        val aspectRatio: Float? = canvas.measuredOrNull()?.let { it.widthPixels.toFloat() / it.heightPixels }

        /** The page's state. */
        val uiState: StateFlow<CarPlayDisplayUiState> =
            combine(
                link?.state?.map { it is CarPlayState.Connected } ?: flowOf(false),
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

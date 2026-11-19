package com.abid.handlebarlink.ui.carplay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.engine.TouchContact
import com.abid.handlebarlink.carplay.engine.android.touch.CarPlayTouch
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import kotlinx.coroutines.delay

/**
 * The CarPlay display with its view model (docs/PLAN.md C4, Paper "CarPlay — flow v2", "Full 5"): the iPhone's
 * picture edge to edge with the system bars hidden. Android's Back (the edge swipe) returns to the CarPlay page while
 * CarPlay keeps running; the decoder only lets go of this view's surface, and coming back asks for a key frame.
 */
@Composable
fun CarPlayDisplayEntry() {
    val viewModel: CarPlayDisplayViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ImmersiveWindow()
    CarPlayDisplay(
        state,
        aspectRatio = viewModel.aspectRatio,
        show = viewModel::show,
        onTouch = viewModel::touch,
        onHintShown = viewModel::hintShown,
    )
}

/**
 * The picture in a `SurfaceView` (hardware overlay, docs/PLAN.md "Performance rules") of the canvas's shape, centred on
 * black; the status line over it while not connected; and the first time only, the "Swipe back" hint for [HINT_MILLIS].
 */
@Composable
fun CarPlayDisplay(
    state: CarPlayDisplayUiState,
    aspectRatio: Float?,
    show: (SurfaceView) -> AutoCloseable,
    onTouch: (List<TouchContact>) -> Unit,
    onHintShown: () -> Unit,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val touch by rememberUpdatedState(onTouch)
    val description = stringResource(R.string.carplay_display)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                CarPlaySurfaceView(context) { event, view ->
                    touch(CarPlayTouch.contacts(event, view.width, view.height))
                }.also { it.handle = show(it) }
            },
            modifier =
                (if (aspectRatio != null) Modifier.aspectRatio(aspectRatio) else Modifier.fillMaxSize())
                    .semantics { contentDescription = description },
            onRelease = { it.handle?.close() },
        )
        if (!state.connected) {
            state.line?.let { line ->
                HlText(
                    line.words(),
                    HlTheme.typography.body,
                    colors.onGlass,
                    Modifier
                        .padding(spacing.s24)
                        .background(colors.glass, CircleShape)
                        .padding(horizontal = spacing.s16, vertical = spacing.s8),
                    textAlign = TextAlign.Center,
                )
            }
        }
        SwipeBackHint(state.hintDue, onHintShown, Modifier.align(Alignment.CenterStart))
    }
}

/** "‹ Swipe back for Handlebar Link" at the edge Back is swiped from: fades in once, and out after [HINT_MILLIS]. */
@Composable
private fun SwipeBackHint(
    due: Boolean?,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val motion = HlTheme.motion
    var visible by remember { mutableStateOf(false) }
    // Decided once, when the stored flag has loaded; marking it shown then changes [due] but not the decision.
    var decided by remember { mutableStateOf(false) }
    LaunchedEffect(due) {
        if (!decided && due != null) {
            decided = true
            if (due) {
                visible = true
                onShown()
            }
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            delay(HINT_MILLIS)
            visible = false
        }
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(motion.fade()), exit = fadeOut(motion.fade())) {
        HlText(
            stringResource(R.string.carplay_display_hint),
            HlTheme.typography.label,
            colors.onGlass,
            Modifier
                .padding(start = spacing.s12)
                .background(colors.glass, CircleShape)
                .padding(horizontal = spacing.s12, vertical = spacing.s6),
        )
    }
}

/** How long the "Swipe back" hint stays (coordinator, 2026-11-19). */
private const val HINT_MILLIS = 3_000L

/**
 * While shown: the system bars hidden (immersive; a swipe from an edge brings them back for a moment, Android "Hide
 * system bars for immersive mode"), the window into the display cutout so the picture is edge to edge, and back again
 * afterwards. Apps targeting SDK 35 run in the cutout on Android 15+ anyway ("Support display cutouts"), so the mode is
 * set only below that, the one branch for it.
 */
@Composable
private fun ImmersiveWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val cutout = window.attributes.layoutInDisplayCutoutMode
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = cutout }
            }
        }
    }
}

/**
 * The view the picture is drawn into: it keeps the screen on while shown, and hands every touch to [onTouch] with
 * itself, so positions are read against its own size. A tap also counts as a click for accessibility services.
 */
@SuppressLint("ViewConstructor") // Built in code only, by CarPlayDisplay.
private class CarPlaySurfaceView(
    context: Context,
    private val onTouch: (MotionEvent, SurfaceView) -> Unit,
) : SurfaceView(context) {
    /** Drawing into this view until closed. */
    var handle: AutoCloseable? = null

    init {
        keepScreenOn = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onTouch(event, this)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

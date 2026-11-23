package com.abid.handlebarlink.ui.carplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abid.handlebarlink.R
import com.abid.handlebarlink.carplay.CarPlayOrientation
import com.abid.handlebarlink.carplay.engine.TouchContact
import com.abid.handlebarlink.carplay.engine.android.CarPlayPictureLayout
import com.abid.handlebarlink.carplay.engine.android.CarPlaySurfaceBounds
import com.abid.handlebarlink.carplay.engine.android.touch.CarPlayTouch
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.ui.navigation.HomeRoute
import com.abid.handlebarlink.ui.navigation.Navigator
import com.abid.handlebarlink.ui.navigation.SettingsRoute
import kotlinx.coroutines.delay

/**
 * The CarPlay display with its view model (docs/PLAN.md C4, Paper "CarPlay — flow v2", "Full 5"): the iPhone's
 * picture full screen with the system bars hidden, the screen turned to the canvas's orientation. Android's Back (the
 * edge swipe) returns to the CarPlay page while CarPlay keeps running; the decoder only lets go of this view's surface,
 * and coming back asks for a key frame.
 */
@Composable
fun CarPlayDisplayEntry(navigator: Navigator) {
    val viewModel: CarPlayDisplayViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shape by viewModel.shape.collectAsStateWithLifecycle()
    ImmersiveWindow()
    LockedOrientation(shape.orientation)
    CarPlayDisplay(
        state,
        shape,
        show = viewModel::show,
        onTouch = viewModel::touch,
        onHintShown = viewModel::hintShown,
        onBack = viewModel::back,
        onHome = { navigator.navigate(HomeRoute) },
        onSettings = { navigator.navigate(SettingsRoute) },
    )
}

/**
 * The picture in a `SurfaceView` (hardware overlay, docs/PLAN.md "Performance rules") on black; the status line over it
 * while not connected; and the first time only, the "Swipe back" hint for [HINT_MILLIS]. In landscape the picture stays
 * out of the display cutout, as the canvas leaves it out ([carPlayWindow]'s notch rule); portrait fills the screen edge
 * to edge. The view is laid out as DiPlay lays out its canvas ([CarPlayPictureLayout]): the area CarPlay draws in fills
 * the window and the rest of the canvas (always to the right of it or below it) falls outside the window, which crops
 * it. Touches are mapped through the same rectangle, so they land at their place in the canvas.
 */
@Composable
fun CarPlayDisplay(
    state: CarPlayDisplayUiState,
    shape: DisplayShape,
    show: (SurfaceView) -> AutoCloseable,
    onTouch: (List<TouchContact>) -> Unit,
    onHintShown: () -> Unit,
    onBack: () -> Unit = {},
    onHome: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    val touch by rememberUpdatedState(onTouch)
    val description = stringResource(R.string.carplay_display)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (shape.orientation == CarPlayOrientation.LANDSCAPE) {
                    Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                } else {
                    Modifier
                },
            ),
    ) {
        val layout = shape.picture?.let { pictureLayout(it, constraints.maxWidth, constraints.maxHeight) }
        val current by rememberUpdatedState(layout)
        AndroidView(
            factory = { context ->
                CarPlaySurfaceView(context) { event, view ->
                    val content = current?.inSurface()
                    touch(
                        if (content == null) {
                            CarPlayTouch.contacts(event, view.width, view.height)
                        } else {
                            CarPlayTouch.contacts(event, content)
                        },
                    )
                }.also { it.handle = show(it) }
            },
            modifier =
                (layout?.let { Modifier.placedAt(it.bounds) } ?: Modifier.fillMaxSize())
                    .semantics { contentDescription = description },
            onRelease = { it.handle?.close() },
        )
        if (!state.connected) state.line?.let { ConnectingLine(it) }
        SwipeBackHint(state.hintDue, onHintShown, Modifier.align(Alignment.CenterStart))
        if (state.connected) {
            CarPlayControlCluster(
                onBack,
                onHome,
                onSettings,
                Modifier.align(Alignment.BottomStart).padding(spacing.s16),
            )
        }
    }
}

/** The status line while CarPlay is not connected: what it is doing, on a glass pill centred over the last picture. */
@Composable
private fun BoxScope.ConnectingLine(line: CarPlayLine) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    HlText(
        line.words(),
        HlTheme.typography.body,
        colors.onGlass,
        Modifier
            .align(Alignment.Center)
            .padding(spacing.s24)
            .background(colors.glass, CircleShape)
            .padding(horizontal = spacing.s16, vertical = spacing.s8),
        textAlign = TextAlign.Center,
    )
}

/** Where [picture]'s canvas goes in a window of [width] x [height] pixels (DiPlay contentRect). */
internal fun pictureLayout(
    picture: PictureSpec,
    width: Int,
    height: Int,
): CarPlayPictureLayout =
    CarPlayPictureLayout.of(picture.canvasWidth, picture.canvasHeight, picture.area, width, height)

/**
 * Measures the view at exactly [bounds]' size, which may be larger than the window, and places it at [bounds]' corner,
 * while taking only the space it is given (DiPlay's SurfaceView bounds, CarPlayHostActivity.kt:6176-6184).
 */
private fun Modifier.placedAt(bounds: CarPlaySurfaceBounds): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints.fixed(bounds.width, bounds.height))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(bounds.left, bounds.top) }
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
 * system bars for immersive mode"), the window into the display cutout so a portrait picture is edge to edge (a
 * landscape one pads itself out of it, CarPlayDisplay), and back again afterwards. Apps targeting SDK 35 run in the
 * cutout on Android 15+ anyway ("Support display cutouts"), so the mode is set only below that, the one branch for it.
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
 * While shown: the screen turned to the canvas's [orientation] (`Activity.setRequestedOrientation`, the documented way
 * to restrict orientation at runtime from Compose: "App orientation restricted on phones", adaptive cookbook), and
 * back to the app's own orientation when the display leaves. The app's own value is remembered across the activity's
 * re-creation for the turn, and not restored while the activity is only being re-created
 * (`isChangingConfigurations`), or the turn would undo itself. Phones honour it; Android 17 ignores it on screens
 * 600 dp and wider, where the picture keeps its shape anyway.
 */
@Composable
private fun LockedOrientation(orientation: CarPlayOrientation) {
    val activity = LocalActivity.current ?: return
    val own = rememberSaveable { activity.requestedOrientation }
    DisposableEffect(activity, orientation) {
        activity.requestedOrientation = screenOrientation(orientation)
        onDispose { restoreOrientation(activity, own) }
    }
}

private fun restoreOrientation(
    activity: Activity,
    own: Int,
) {
    if (!activity.isChangingConfigurations) activity.requestedOrientation = own
}

/**
 * The activity orientation for a canvas in [orientation]: landscape or portrait, either way up as the rider's
 * auto-rotate allows (`userLandscape`, `userPortrait`), so a phone mounted with its notch on the right still shows the
 * picture upright.
 */
internal fun screenOrientation(orientation: CarPlayOrientation): Int =
    when (orientation) {
        CarPlayOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        CarPlayOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
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

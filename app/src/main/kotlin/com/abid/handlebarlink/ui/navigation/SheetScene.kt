package com.abid.handlebarlink.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where a sheet rests: on screen, or below it. */
enum class SheetValue { Shown, Hidden }

/** Entry metadata that shows the entry as a sheet ([SheetSceneStrategy]). */
fun sheet(): Map<String, Any> = mapOf(SHEET to true)

/**
 * Entries marked with [sheet] as a bottom sheet over the page under them: Navigation 3's bottom-sheet
 * recipe (an OverlayScene whose onRemove runs the close animation before the entry goes), with the app's own
 * sheet instead of Material's ModalBottomSheet. Before any non-overlay strategy, as the recipe says.
 */
class SheetSceneStrategy<T : Any> : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val last = entries.lastOrNull()?.takeIf { it.metadata[SHEET] == true } ?: return null
        return SheetScene(last.contentKey, entries.dropLast(1), last, onBack)
    }
}

/**
 * The sheet: [entry] anchored to the bottom over a scrim. It rises when shown and follows the finger
 * ("Drag composables": anchoredDraggable, anchors from the measured height, the position read only while
 * placing); released, it settles to the nearer anchor or flings, and settling below closes it (Back). A
 * scroll inside hands over to the sheet at its top (pulling down drags the sheet; scrolling up first lifts a
 * part-dragged sheet), as Material's sheet does. Back (followed with the finger), a tap on the scrim, Cancel and
 * Save all pop the entry once, and [onRemove] slides the sheet away before it goes.
 */
class SheetScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    private val entry: NavEntry<T>,
    private val onBack: () -> Unit,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)
    override val overlaidEntries: List<NavEntry<T>> = previousEntries

    private var state: AnchoredDraggableState<SheetValue>? = null
    private var spec: AnimationSpec<Float>? = null
    private var removing = false

    override val content: @Composable () -> Unit = {
        val motion = HlTheme.motion
        val scrim = HlTheme.colors.scrim
        val state = remember { AnchoredDraggableState(SheetValue.Hidden) }
        val spec = motion.transform<Float>()
        this.state = state
        this.spec = spec
        val fling =
            AnchoredDraggableDefaults.flingBehavior(
                state,
                positionalThreshold = { distance -> distance * HALF },
                animationSpec = spec,
            )
        LaunchedEffect(state) {
            // Rise once the sheet is measured (its anchors exist).
            snapshotFlow { state.anchors.size }.first { it > 0 }
            state.animateTo(SheetValue.Shown, spec)
            // Settled below by a drag or fling: the rider closed it.
            snapshotFlow { state.settledValue }
                .drop(1)
                .filter { it == SheetValue.Hidden && !removing }
                .first()
            onBack()
        }
        // Its own window above the app, as Material's sheet has: the page under it does not change while
        // the sheet moves (nothing re-recorded or re-blurred, the tab bar included), and Back reaches the
        // sheet first. No window dim or animation: the scrim and the spring are drawn here.
        Dialog(onDismissRequest = onBack, properties = WINDOW) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.setDimAmount(0f)
                window?.setWindowAnimations(0)
            }
            // Back belongs to the sheet: NavDisplay's own handler would pop every entry the sheet covers (the
            // page under it too), as its scene is the page's. A back swipe draws the sheet down with the finger
            // (predictive back, "Access progress manually"); released, the entry pops and onRemove finishes the
            // slide; cancelled, the sheet springs back up.
            val reset = rememberCoroutineScope()
            PredictiveBackHandler { progress ->
                try {
                    progress.collect { event ->
                        val below = state.anchors.positionOf(SheetValue.Hidden)
                        if (!below.isNaN()) state.dispatchRawDelta(event.progress * below * BACK_SHARE - state.offset)
                    }
                    onBack()
                } catch (cancelled: CancellationException) {
                    reset.launch { state.animateTo(SheetValue.Shown, spec) }
                    throw cancelled
                }
            }
            Box(Modifier.fillMaxSize()) {
                Spacer(
                    Modifier
                        .fillMaxSize()
                        // The scrim follows the sheet: full when shown, none when below the screen.
                        .drawBehind { drawRect(scrim, alpha = shown(state)) }
                        .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onBack),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        // A sheet taller than the screen stops below the status bar (it scrolls instead).
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .widthIn(max = HlTheme.sizes.contentMax)
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val sheet = measurable.measure(constraints)
                            state.updateAnchors(
                                DraggableAnchors {
                                    SheetValue.Shown at 0f
                                    SheetValue.Hidden at sheet.height.toFloat()
                                },
                            )
                            layout(sheet.width, sheet.height) {
                                // Read while placing only: dragging never recomposes. Below the screen until shown.
                                val y = state.offset.takeUnless { it.isNaN() } ?: sheet.height.toFloat()
                                sheet.place(0, y.roundToInt())
                            }
                        }.nestedScroll(remember(state) { HandOver(state, fling) })
                        .anchoredDraggable(state, Orientation.Vertical, flingBehavior = fling),
                ) { entry.Content() }
            }
        }
    }

    override suspend fun onRemove() {
        removing = true
        val state = state ?: return
        state.animateTo(SheetValue.Hidden, spec ?: return)
    }

    // A scene is the same scene while it shows the same entry over the same pages (Navigation 3 compares
    // scenes; the back callback is not part of that).
    override fun equals(other: Any?): Boolean =
        other is SheetScene<*> && key == other.key && entry == other.entry && previousEntries == other.previousEntries

    override fun hashCode(): Int = 31 * (31 * key.hashCode() + entry.hashCode()) + previousEntries.hashCode()
}

/** How far the sheet is shown, 0 (below) to 1 (up). */
private fun shown(state: AnchoredDraggableState<SheetValue>): Float {
    val below = state.anchors.positionOf(SheetValue.Hidden)
    val offset = state.offset
    return if (below.isNaN() || below <= 0f || offset.isNaN()) 0f else (1f - offset / below).coerceIn(0f, 1f)
}

/**
 * Scrolling inside the sheet and dragging it as one gesture: a part-dragged sheet rises before the content
 * scrolls up; at the content's top, pulling down drags the sheet; flings end in the sheet.
 */
private class HandOver(
    private val state: AnchoredDraggableState<SheetValue>,
    private val flingBehavior: TargetedFlingBehavior,
) : NestedScrollConnection {
    /**
     * A fling from the content, exactly as the anchoredDraggable modifier flings: inside the state's
     * anchoredDrag, each step clamped to the anchors, so the state settles (and knows where). Moving the
     * sheet outside it left the state "shown" with the sheet below the screen and its scrim still taking taps.
     */
    private suspend fun fling(velocity: Float) {
        state.anchoredDrag { anchors ->
            val scope =
                object : ScrollScope {
                    override fun scrollBy(pixels: Float): Float {
                        val from = state.offset
                        val to = (from + pixels).coerceIn(anchors.minPosition(), anchors.maxPosition())
                        dragTo(to)
                        return to - from
                    }
                }
            with(flingBehavior) { scope.performFling(velocity) }
        }
    }

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset =
        if (available.y < 0f && source == NestedScrollSource.UserInput) {
            Offset(0f, state.dispatchRawDelta(available.y))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val up = state.anchors.positionOf(SheetValue.Shown)
        return if (available.y < 0f && state.offset > up) {
            fling(available.y)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity,
    ): Velocity {
        fling(available.y)
        return available
    }
}

private const val SHEET = "hl.sheet"

/** Full screen and edge to edge; the sheet handles Back and taps outside itself. */
private val WINDOW =
    DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )

/** How far a back swipe draws the sheet down, as a share of its height, before it is released. */
private const val BACK_SHARE = 0.33f

/** Released past half its height, the sheet closes. */
private const val HALF = 0.5f

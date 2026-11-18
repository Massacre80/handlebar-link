package com.abid.handlebarlink.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.abid.handlebarlink.R
import com.abid.handlebarlink.controls.PressFeedback
import com.abid.handlebarlink.controls.RiderAction
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.designsystem.component.HlIcon
import com.abid.handlebarlink.core.designsystem.component.HlText
import com.abid.handlebarlink.core.designsystem.theme.HlTheme
import com.abid.handlebarlink.core.designsystem.theme.HlThemeVariant
import com.abid.handlebarlink.ui.controls.actionIcon
import com.abid.handlebarlink.ui.controls.actionName
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a handlebar press did, as a small banner at the top of the screen over CarPlay for a moment
 * ("Next track", "Brightness up · 70%", "It's 8:30"), in a window above other apps ("Display over other
 * apps", Permissions page; without it nothing is shown). Volume and mute are left to the system's own volume
 * bar. The window is added on the first press and removed when the banner goes.
 */
@Singleton
class PressBanner
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        logger: Logger,
    ) : PressFeedback {
        private val log = logger.child("overlay")
        private val main = Handler(Looper.getMainLooper())
        private val windows = context.getSystemService(WindowManager::class.java)
        private var shown: Shown? = null
        private val hide = Runnable { remove() }

        override fun done(
            action: RiderAction,
            note: String?,
        ) {
            if (action in SYSTEM_SHOWN || action == RiderAction.NOTHING) return
            val name = context.getString(actionName(action))
            show(Banner(actionIcon(action), if (note == null) name else "$name · $note"))
        }

        override fun heldForCall(action: RiderAction) {
            show(
                Banner(
                    actionIcon(action),
                    "${context.getString(actionName(action))} · ${context.getString(R.string.action_held_for_call)}",
                ),
            )
        }

        private fun show(banner: Banner) {
            if (!Settings.canDrawOverlays(context)) return
            main.post {
                val current = shown ?: add() ?: return@post
                current.banner.value = banner
                main.removeCallbacks(hide)
                main.postDelayed(hide, SHOW_MILLIS)
            }
        }

        private fun add(): Shown? {
            val owner = WindowOwner()
            val banner = mutableStateOf<Banner?>(null)
            val view =
                ComposeView(context).apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setContent { HlTheme(HlThemeVariant.NIGHT) { banner.value?.let { BannerPill(it) } } }
                }
            val params =
                WindowManager
                    .LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = (TOP_DP * context.resources.displayMetrics.density).toInt()
                    }
            // The permission can be taken away between the check and here; then there is just no banner.
            val failure =
                try {
                    windows.addView(view, params)
                    null
                } catch (e: WindowManager.BadTokenException) {
                    e
                } catch (e: SecurityException) {
                    e
                }
            if (failure != null) {
                log.warn("overlay.add_failed", "error" to failure)
                owner.destroy()
                return null
            }
            owner.resume()
            return Shown(view, owner, banner).also { shown = it }
        }

        private fun remove() {
            val current = shown ?: return
            shown = null
            try {
                windows.removeView(current.view)
            } catch (e: IllegalArgumentException) {
                // Already gone with its window (the system removed it).
                log.debug("overlay.remove_gone", "error" to e)
            }
            current.owner.destroy()
        }

        private class Shown(
            val view: ComposeView,
            val owner: WindowOwner,
            val banner: MutableState<Banner?>,
        )
    }

/** A banner's icon and text. */
internal data class Banner(
    val icon: Int,
    val text: String,
)

/** The banner: the action's icon in the accent and its text, on the dark pill the switch diagram's callouts use. */
@Composable
internal fun BannerPill(banner: Banner) {
    val colors = HlTheme.colors
    val spacing = HlTheme.spacing
    Row(
        Modifier
            .clip(CircleShape)
            .background(colors.surfaceInverse)
            .padding(horizontal = spacing.s16, vertical = spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s8),
    ) {
        HlIcon(banner.icon, colors.brand, size = HlTheme.sizes.iconSmall)
        HlText(banner.text, HlTheme.typography.value, colors.inkInverse, maxLines = 1)
    }
}

/**
 * The lifecycle and saved state a Compose view needs outside an activity (a window of its own): resumed
 * while the banner shows, destroyed with the window.
 */
private class WindowOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    init {
        saved.performAttach()
        saved.performRestore(null)
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** Shown by the system's own volume bar. */
private val SYSTEM_SHOWN = setOf(RiderAction.VOLUME_UP, RiderAction.VOLUME_DOWN, RiderAction.MUTE)
private const val SHOW_MILLIS = 1_500L
private const val TOP_DP = 48

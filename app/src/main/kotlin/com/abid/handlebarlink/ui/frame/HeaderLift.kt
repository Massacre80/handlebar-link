package com.abid.handlebarlink.ui.frame

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether a tab page's content is under the app header, so the header lifts into its glass (docs/PLAN.md "App
 * header"; Material's container change on scroll). It is read from the page's own scroll state: a page's scrollers
 * register here ([rememberPageScrollState]) and the header is lifted while any of them is scrolled away from its
 * top. At the top (every offset 0) it is at rest: no glass, no tint, nothing drawn behind it (owner, 2026-11-20: "on
 * scroll is fine, not initially"). Each tab page has its own scrollers, so a tab at its top never shows the glass,
 * whatever the last tab did; on its side a page has two panes and either one scrolled lifts it. The scroll states keep
 * themselves across configuration changes, so nothing here is saved.
 */
@Stable
class HeaderLift {
    private class Source(
        val page: String,
        val offset: () -> Int,
    )

    private val sources = mutableStateListOf<Source>()

    /** [page]'s scroller, whose [offset] is how far it has scrolled from its top; returns the token to [unregister]. */
    fun register(
        page: String,
        offset: () -> Int,
    ): Any = Source(page, offset).also { sources += it }

    /** The scroller registered as [token] left the screen. */
    fun unregister(token: Any) {
        sources.remove(token)
    }

    /** Whether [page]'s content is under the header: one of its scrollers is away from its top. */
    fun isLifted(page: String): Boolean = sources.any { it.page == page && it.offset() > 0 }
}

/** The tab pages' [HeaderLift], provided by the app's frame; null outside it (previews), where nothing registers. */
val LocalHeaderLift = staticCompositionLocalOf<HeaderLift?> { null }

/** The page a scroller belongs to, provided around each page by the app's frame; null outside it. */
val LocalHeaderPage = staticCompositionLocalOf<String?> { null }

/**
 * [rememberScrollState] for a tab page's scroller under the app header: the header lifts into its glass while this
 * scroller is away from its top, and rests again at the top.
 */
@Composable
fun rememberPageScrollState(): ScrollState {
    val scroll = rememberScrollState()
    val lift = LocalHeaderLift.current
    val page = LocalHeaderPage.current
    if (lift != null && page != null) {
        DisposableEffect(lift, page, scroll) {
            val token = lift.register(page) { scroll.value }
            onDispose { lift.unregister(token) }
        }
    }
    return scroll
}

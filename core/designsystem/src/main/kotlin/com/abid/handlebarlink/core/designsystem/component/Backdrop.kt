package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.unit.Dp

/**
 * What glass cards blur behind them (the design's `backdrop-filter`): a [backdropSource] records its
 * drawing here and [backdropBlur] redraws it, blurred, behind a card. Compose's `Modifier.blur` only blurs a
 * composable's own content ("Blurring in Compose": surface, not backdrop, blur), so the backdrop is drawn
 * again from a recorded [GraphicsLayer] ("Graphics layers": record, then draw the layer).
 */
@Stable
public class Backdrop internal constructor() {
    // Set by the source node while attached; plain fields, read while drawing (no state, no recomposition).
    internal var layer: GraphicsLayer? = null
    internal var source: Modifier.Node? = null

    /** Counts the source's recordings; a glass records again when it changed (the bike arrives late). */
    internal var generation: Int = 0

    /** The glass drawn from this backdrop, told to redraw when the source records new content. */
    internal val glasses: MutableSet<DrawModifierNode> = mutableSetOf()
}

/** A [Backdrop] for one screen. */
@Composable
public fun rememberBackdrop(): Backdrop = remember { Backdrop() }

/** Draws this content as usual and records it into [backdrop] for the glass in front of it. */
public fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this then BackdropSourceElement(backdrop)

/**
 * Draws [backdrop] behind this content, where it lies on screen, blurred by [blur] (a CSS blur: the
 * standard deviation) and saturated by [saturation] (CSS `saturate()`). Put it after the card's clip and
 * before its fill, so the fill tints the glass and the clip shapes it.
 *
 * CSS `backdrop-filter` replaces what lies behind with its blurred copy. Drawing the copy over a backdrop
 * that is not opaque (the bike fades out towards the cards) let the sharp original show through (seen on
 * the phone, 2026-11-17), so a backdrop that is not opaque needs a [base]: the ground it lies on, painted
 * first. A backdrop that includes its own ground (the whole page, for the tab bar) needs none.
 *
 * Positions are read while drawing (`requireLayoutCoordinates`), not tracked with `onGloballyPositioned`,
 * which fired on every frame of a page slide; the blur and colour filter are built once, and the glass is
 * re-recorded only when its offset or size changes (Compose performance skill, layouts and graphics).
 */
public fun Modifier.backdropBlur(
    backdrop: Backdrop,
    blur: Dp,
    saturation: Float,
    base: Color = Color.Transparent,
): Modifier = this then BackdropBlurElement(backdrop, blur, saturation, base)

private data class BackdropSourceElement(
    val backdrop: Backdrop,
) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(backdrop)

    override fun update(node: BackdropSourceNode) {
        node.attachTo(backdrop)
    }
}

private class BackdropSourceNode(
    private var backdrop: Backdrop,
) : Modifier.Node(),
    DrawModifierNode {
    override fun onAttach() {
        backdrop.layer = requireGraphicsContext().createGraphicsLayer()
        backdrop.source = this
    }

    override fun onDetach() {
        backdrop.layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        backdrop.layer = null
        if (backdrop.source === this) backdrop.source = null
    }

    fun attachTo(next: Backdrop) {
        if (next === backdrop) return
        onDetach()
        backdrop = next
        onAttach()
    }

    override fun ContentDrawScope.draw() {
        val layer = backdrop.layer
        if (layer == null) {
            drawContent()
            return
        }
        layer.record { this@draw.drawContent() }
        drawLayer(layer)
        // The glass recorded the old content; it must draw again (an invalidation for the next frame only:
        // a glass redraw never redraws the source, so this cannot loop).
        backdrop.generation++
        backdrop.glasses.forEach { it.invalidateDraw() }
    }
}

private data class BackdropBlurElement(
    val backdrop: Backdrop,
    val blur: Dp,
    val saturation: Float,
    val base: Color,
) : ModifierNodeElement<BackdropBlurNode>() {
    override fun create() = BackdropBlurNode(backdrop, blur, saturation, base)

    override fun update(node: BackdropBlurNode) {
        node.update(backdrop, blur, saturation, base)
    }
}

private class BackdropBlurNode(
    private var backdrop: Backdrop,
    private var blur: Dp,
    private var saturation: Float,
    private var base: Color,
) : Modifier.Node(),
    DrawModifierNode {
    private var glass: GraphicsLayer? = null
    private var shift: Offset = Offset.Unspecified
    private var recordedSize: Size = Size.Unspecified
    private var effectFor: Float = Float.NaN
    private var recordedGeneration: Int = -1

    fun update(
        backdrop: Backdrop,
        blur: Dp,
        saturation: Float,
        base: Color,
    ) {
        if (backdrop !== this.backdrop && isAttached) {
            this.backdrop.glasses -= this
            backdrop.glasses += this
        }
        this.backdrop = backdrop
        this.blur = blur
        this.saturation = saturation
        this.base = base
        effectFor = Float.NaN
        shift = Offset.Unspecified
        invalidateDraw()
    }

    override fun onAttach() {
        glass = requireGraphicsContext().createGraphicsLayer()
        backdrop.glasses += this
    }

    override fun onDetach() {
        backdrop.glasses -= this
        glass?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        glass = null
        shift = Offset.Unspecified
        effectFor = Float.NaN
        recordedGeneration = -1
    }

    override fun ContentDrawScope.draw() {
        val source = backdrop.layer
        val sourceNode = backdrop.source?.takeIf { it.isAttached }
        val glass = glass
        if (source != null && glass != null && sourceNode != null) {
            // Where the backdrop's origin lies in this card's coordinates, read now rather than tracked.
            val offset = requireLayoutCoordinates().localPositionOf(sourceNode.requireLayoutCoordinates(), Offset.Zero)
            val radius = blur.toPx()
            if (radius != effectFor) {
                // hwui turns a blur radius into a standard deviation as 0.57735 r + 0.5; CSS gives the deviation.
                val skia = ((radius - SIGMA_OFFSET) / SIGMA_PER_RADIUS).coerceAtLeast(0f)
                glass.renderEffect = if (skia > 0f) BlurEffect(skia, skia, TileMode.Clamp) else null
                glass.colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
                effectFor = radius
                shift = Offset.Unspecified
            }
            if (offset != shift || size != recordedSize || backdrop.generation != recordedGeneration) {
                glass.record { translate(offset.x, offset.y) { drawLayer(source) } }
                shift = offset
                recordedSize = size
                recordedGeneration = backdrop.generation
            }
            if (base.alpha > 0f) drawRect(base)
            drawLayer(glass)
        }
        drawContent()
    }

    private companion object {
        const val SIGMA_PER_RADIUS = 0.57735f
        const val SIGMA_OFFSET = 0.5f
    }
}

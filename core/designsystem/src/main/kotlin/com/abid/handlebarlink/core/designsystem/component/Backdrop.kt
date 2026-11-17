package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.Dp

/**
 * What glass cards blur behind them (the design's `backdrop-filter`): a [backdropSource] records its
 * drawing here and [backdropBlur] redraws it, blurred, behind a card. Compose's `Modifier.blur` only blurs a
 * composable's own content ("Blurring in Compose": surface, not backdrop, blur), so the backdrop is drawn
 * again from a recorded [GraphicsLayer] ("Graphics layers": record, then draw the layer).
 */
@Stable
public class Backdrop internal constructor() {
    internal var layer: GraphicsLayer? by mutableStateOf(null)
    internal var origin: Offset by mutableStateOf(Offset.Unspecified)
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
 */
public fun Modifier.backdropBlur(
    backdrop: Backdrop,
    blur: Dp,
    saturation: Float,
): Modifier = this then BackdropBlurElement(backdrop, blur, saturation)

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
    DrawModifierNode,
    GlobalPositionAwareModifierNode {
    override fun onAttach() {
        backdrop.layer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        backdrop.layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        backdrop.layer = null
        backdrop.origin = Offset.Unspecified
    }

    fun attachTo(next: Backdrop) {
        if (next === backdrop) return
        onDetach()
        backdrop = next
        onAttach()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        backdrop.origin = coordinates.positionInRoot()
    }

    override fun ContentDrawScope.draw() {
        val layer = backdrop.layer
        if (layer == null) {
            drawContent()
            return
        }
        layer.record { this@draw.drawContent() }
        drawLayer(layer)
    }
}

private data class BackdropBlurElement(
    val backdrop: Backdrop,
    val blur: Dp,
    val saturation: Float,
) : ModifierNodeElement<BackdropBlurNode>() {
    override fun create() = BackdropBlurNode(backdrop, blur, saturation)

    override fun update(node: BackdropBlurNode) {
        node.backdrop = backdrop
        node.blur = blur
        node.saturation = saturation
        node.invalidateDraw()
    }
}

private class BackdropBlurNode(
    var backdrop: Backdrop,
    var blur: Dp,
    var saturation: Float,
) : Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {
    private var glass: GraphicsLayer? = null
    private var origin by mutableStateOf(Offset.Unspecified)

    override fun onAttach() {
        glass = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        glass?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        glass = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        origin = coordinates.positionInRoot()
    }

    override fun ContentDrawScope.draw() {
        val source = backdrop.layer
        val glass = glass
        // Where the backdrop's origin lies from this card's (null until both are placed).
        val shift = if (backdrop.origin.isSpecified && origin.isSpecified) backdrop.origin - origin else null
        if (source != null && glass != null && shift != null) {
            // hwui turns a blur radius into a standard deviation as 0.57735 r + 0.5; CSS gives the deviation.
            val radius = ((blur.toPx() - SIGMA_OFFSET) / SIGMA_PER_RADIUS).coerceAtLeast(0f)
            glass.renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
            glass.colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
            glass.record { translate(shift.x, shift.y) { drawLayer(source) } }
            drawLayer(glass)
        }
        drawContent()
    }

    private companion object {
        const val SIGMA_PER_RADIUS = 0.57735f
        const val SIGMA_OFFSET = 0.5f
    }
}

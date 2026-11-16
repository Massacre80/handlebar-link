package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The design's press feedback (Android "Create a custom Indication"): pressed content springs to
 * [pressedScale], and keyboard focus draws a ring in [focusColor]. Every `clickable`/`selectable` uses it
 * through `LocalIndication` (set by HlTheme). The scale is read while drawing, so a press never recomposes.
 */
public data class HlIndication(
    /** Scale while pressed. */
    val pressedScale: Float,
    /** The spring towards and back from [pressedScale]. */
    val spec: AnimationSpec<Float>,
    /** The keyboard focus ring. */
    val focusColor: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HlIndicationNode(interactionSource, pressedScale, spec, focusColor)
}

private class HlIndicationNode(
    private val interactions: InteractionSource,
    private val pressedScale: Float,
    private val spec: AnimationSpec<Float>,
    private val focusColor: Color,
) : Modifier.Node(),
    DrawModifierNode {
    private val scale = Animatable(1f)
    private var focused by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            val focuses = mutableListOf<FocusInteraction.Focus>()
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        launch { scale.animateTo(pressedScale, spec) }
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        launch { scale.animateTo(1f, spec) }
                    }

                    is FocusInteraction.Focus -> {
                        focuses += interaction
                    }

                    is FocusInteraction.Unfocus -> {
                        focuses -= interaction.focus
                    }

                    else -> {}
                }
                focused = focuses.isNotEmpty()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        scale(scale.value) { this@draw.drawContent() }
        if (focused) drawRect(focusColor, style = Stroke(width = FOCUS_RING.toPx()))
    }

    private companion object {
        val FOCUS_RING = 2.dp
    }
}

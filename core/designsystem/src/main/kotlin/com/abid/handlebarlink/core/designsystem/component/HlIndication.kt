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
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The design's press feedback drawn over a clickable (Android "Create a custom Indication"): a state layer
 * in [pressedColor] that fades in while pressed, and a ring in [focusColor] for keyboard focus. Every
 * `clickable`/`selectable` uses it through `LocalIndication` (set by HlTheme). Components clip before their
 * clickable, so the layer takes their shape; the whole component's spring is [pressScale]. Animations are
 * read while drawing, so a press never recomposes.
 */
public data class HlIndication(
    /** The state layer at full press. */
    val pressedColor: Color,
    /** The spring of the state layer. */
    val spec: AnimationSpec<Float>,
    /** The keyboard focus ring. */
    val focusColor: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HlIndicationNode(interactionSource, pressedColor, spec, focusColor)
}

private class HlIndicationNode(
    private val interactions: InteractionSource,
    private val pressedColor: Color,
    private val spec: AnimationSpec<Float>,
    private val focusColor: Color,
) : Modifier.Node(),
    DrawModifierNode {
    private val pressed = Animatable(0f)
    private var focused by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            val focuses = mutableListOf<FocusInteraction.Focus>()
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        launch { pressed.animateTo(1f, spec) }
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        launch { pressed.animateTo(0f, spec) }
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
        drawContent()
        val layer = pressed.value
        if (layer > 0f) drawRect(pressedColor, alpha = layer)
        if (focused) drawRect(focusColor, style = Stroke(width = FOCUS_RING.toPx()))
    }

    private companion object {
        val FOCUS_RING = 2.dp
    }
}

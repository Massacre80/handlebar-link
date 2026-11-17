package com.abid.handlebarlink.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import com.abid.handlebarlink.core.designsystem.theme.HlMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The whole component (fill, border and content) springs to [HlMotion.pressScale] while [interactions]
 * reports a press. Put it first in the component's chain and give its clickable the same interaction
 * source; the state layer comes from [HlIndication]. The scale is applied in the layer, so a press never
 * recomposes or re-measures.
 */
public fun Modifier.pressScale(
    interactions: InteractionSource,
    motion: HlMotion,
): Modifier = this then PressScaleElement(interactions, motion)

private data class PressScaleElement(
    val interactions: InteractionSource,
    val motion: HlMotion,
) : ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(interactions, motion)

    override fun update(node: PressScaleNode) = node.update(interactions, motion)
}

private class PressScaleNode(
    private var interactions: InteractionSource,
    private var motion: HlMotion,
) : Modifier.Node(),
    LayoutModifierNode {
    private val scale = Animatable(1f)
    private var watching: Job? = null

    override fun onAttach() = watch()

    fun update(
        interactions: InteractionSource,
        motion: HlMotion,
    ) {
        this.motion = motion
        if (interactions != this.interactions) {
            this.interactions = interactions
            if (isAttached) watch()
        }
    }

    private fun watch() {
        watching?.cancel()
        watching =
            coroutineScope.launch {
                interactions.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> {
                            launch { scale.animateTo(motion.pressScale, motion.press()) }
                        }

                        is PressInteraction.Release, is PressInteraction.Cancel -> {
                            launch { scale.animateTo(1f, motion.press()) }
                        }

                        else -> {}
                    }
                }
            }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }
}

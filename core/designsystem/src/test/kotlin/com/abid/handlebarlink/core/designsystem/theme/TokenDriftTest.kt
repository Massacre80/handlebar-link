package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Kotlin theme must equal the token files in `design/` (the Paper export and the Night set). */
class TokenDriftTest {
    private fun tokens(file: String): Map<String, String> {
        val css = File("design/$file").readText()
        val raw =
            Regex("""--([a-z0-9-]+):\s*([^;]+);""").findAll(css).associate {
                it.groupValues[1] to
                    it.groupValues[2].trim()
            }

        fun resolve(value: String): String =
            Regex("""^var\(--([a-z0-9-]+)\)$""").find(value)?.let { resolve(raw.getValue(it.groupValues[1])) } ?: value
        return raw.mapValues { resolve(it.value) }
    }

    /** ARGB of a CSS colour: #RRGGBB, #RRGGBBAA or rgb(r g b / a%). */
    private fun argb(css: String): Int {
        Regex("""^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$""").find(css)?.let {
            val rgb = it.groupValues[1].toInt(16)
            val alpha = it.groupValues[2].ifEmpty { "FF" }.toInt(16)
            return (alpha shl 24) or rgb
        }
        val parts =
            Regex("""^rgb\((\d+) (\d+) (\d+) / (\d+)%\)$""")
                .find(css)
                ?.groupValues
                ?.drop(1)
                ?.map(String::toInt)
                ?: error("unparsed colour $css")
        val alpha = (parts[3] * 255 / 100.0).roundToInt()
        return (alpha shl 24) or (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
    }

    private fun camel(token: String) =
        token
            .split('-')
            .mapIndexed { i, p ->
                if (i ==
                    0
                ) {
                    p
                } else {
                    p.replaceFirstChar(Char::uppercase)
                }
            }.joinToString("")

    /** Field names in declaration order, read from the data class's toString. */
    private fun fieldsOf(colors: HlColors) =
        Regex("""(\w+)=Color""").findAll(colors.toString()).map { it.groupValues[1] }.toList()

    private fun HlColors.byName(): Map<String, Color> =
        fieldsOf(this)
            .zip(Regex("""=(Color\([^)]*\))""").findAll(toString()).map { it.groupValues[1] }.toList())
            .associate { (name, _) -> name to field(name) }

    private fun HlColors.field(name: String): Color =
        when (name) {
            "ink" -> ink
            "inkSoft" -> inkSoft
            "inkMuted" -> inkMuted
            "inkInverse" -> inkInverse
            "inkInverseMuted" -> inkInverseMuted
            "ground" -> ground
            "panel" -> panel
            "raisedHi" -> raisedHi
            "raisedLo" -> raisedLo
            "well" -> well
            "line" -> line
            "lineSoft" -> lineSoft
            "lineStrong" -> lineStrong
            "surfaceInverse" -> surfaceInverse
            "surfaceInverseEdge" -> surfaceInverseEdge
            "brand" -> brand
            "brandHi" -> brandHi
            "brandLo" -> brandLo
            "brandDeep" -> brandDeep
            "brandInk" -> brandInk
            "brandTint" -> brandTint
            "success" -> success
            "successTint" -> successTint
            "danger" -> danger
            "glass" -> glass
            "glassEdge" -> glassEdge
            "glassLine" -> glassLine
            "onGlass" -> onGlass
            "onGlassMuted" -> onGlassMuted
            "screen" -> screen
            "pill" -> pill
            "glowInner" -> glowInner
            "glowMid" -> glowMid
            "signal" -> signal
            "action" -> action
            "onAction" -> onAction
            "track" -> track
            "gauge" -> gauge
            "shadow" -> shadow
            "scrim" -> scrim
            else -> error("no field $name")
        }

    private fun assertColors(
        file: String,
        colors: HlColors,
    ) {
        val css = tokens(file).filterKeys { it.startsWith("color-") }.mapKeys { camel(it.key.removePrefix("color-")) }
        val kotlin = colors.byName()

        assertThat(kotlin.keys.sorted(), "colour tokens of $file").isEqualTo(css.keys.sorted())
        css.forEach { (name, value) ->
            val expected = argb(value)
            val actual = kotlin.getValue(name).toArgb()
            assertThat(actual and 0xFFFFFF, "$file $name rgb").isEqualTo(expected and 0xFFFFFF)
            // Percent alphas round to one step of 255.
            assertThat(abs((actual ushr 24) - (expected ushr 24)), "$file $name alpha").isLessThanOrEqualTo(1)
        }
    }

    @Test
    fun `Bento colours equal the design export`() = assertColors("bento.css", BentoColors)

    @Test
    fun `Night colours equal the Night token set`() = assertColors("night.css", NightColors)

    @Test
    fun `text styles use the type tokens`() {
        val css = tokens("bento.css")

        fun px(name: String) = css.getValue(name).removeSuffix("px").toFloat()

        fun em(name: String) = css.getValue(name).removeSuffix("em").toFloat()
        val t = HlTypographyDefault
        val expected: Map<String, Triple<TextStyle, Pair<String, String>, String>> =
            mapOf(
                "meta" to Triple(t.meta, "text-meta" to "leading-meta", "tracking-tight"),
                "label" to Triple(t.label, "text-label" to "leading-label", "tracking-tight"),
                "body" to Triple(t.body, "text-body" to "leading-body", "tracking-tight"),
                "value" to Triple(t.value, "text-value" to "leading-lead", "tracking-tighter"),
                "lead" to Triple(t.lead, "text-lead" to "leading-lead", "tracking-tighter"),
                "title" to Triple(t.title, "text-title" to "leading-title", "tracking-heading"),
                "display" to Triple(t.display, "text-display" to "leading-display", "tracking-hero"),
                "headline" to Triple(t.headline, "text-headline" to "leading-headline", "tracking-hero"),
                "tab" to Triple(t.tab, "text-tab" to "leading-tab", "tracking-tight"),
                "caps" to Triple(t.caps, "text-tab" to "leading-tab", "tracking-caps"),
                "overline" to Triple(t.overline, "text-tab" to "leading-tab", "tracking-wide"),
            )
        expected.forEach { (name, spec) ->
            val (style, sizes, tracking) = spec
            assertThat(style.fontSize.value, "$name size").isEqualTo(px(sizes.first))
            assertThat(style.lineHeight.value, "$name leading").isEqualTo(px(sizes.second))
            assertThat(style.letterSpacing.value, "$name tracking").isEqualTo(em(tracking))
        }
    }

    @Test
    fun `spacing, sizes, radii and strokes use the dimension tokens`() {
        val css = tokens("bento.css")

        fun dp(name: String) = css.getValue(name).removeSuffix("px").toFloat()
        val expected = dimensionTokens()
        val prefixes = listOf("spacing-", "size-", "radius-", "stroke-", "dash-")
        val tokens = css.keys.filter { key -> prefixes.any { key.startsWith(it) } }

        assertThat(expected.keys.sorted()).isEqualTo(tokens.sorted())
        expected.forEach { (name, value) -> assertThat(value.value, name).isEqualTo(dp(name)) }
    }

    @Test
    fun `shadows use each look's shadow tokens`() {
        listOf("bento.css" to BentoShadows, "night.css" to NightShadows).forEach { (file, shadows) ->
            val css = tokens(file)

            fun shadow(name: String): HlShadow {
                val parts = css.getValue(name).split(' ').map { it.removeSuffix("px").toFloat() }
                return HlShadow(parts[1].dp, parts[2].dp)
            }
            assertThat(shadows.bar, "$file bar").isEqualTo(shadow("shadow-bar"))
            assertThat(shadows.chip, "$file chip").isEqualTo(shadow("shadow-chip"))
            assertThat(shadows.floating, "$file floating").isEqualTo(shadow("shadow-floating"))
        }
    }

    private fun dimensionTokens(): Map<String, Dp> {
        val s = HlSpacing()
        val r = HlRadii()
        val k = HlStrokes()
        return sizeTokens() +
            mapOf(
                "spacing-4" to s.s4,
                "spacing-8" to s.s8,
                "spacing-12" to s.s12,
                "spacing-14" to s.s14,
                "spacing-16" to s.s16,
                "spacing-20" to s.s20,
                "spacing-24" to s.s24,
                "spacing-2" to s.s2,
                "spacing-6" to s.s6,
                "spacing-10" to s.s10,
                "spacing-18" to s.s18,
                "spacing-22" to s.s22,
                "spacing-28" to s.s28,
                "radius-card" to r.card,
                "radius-hero" to r.hero,
                "radius-full" to r.full,
                "radius-nested" to r.nested,
                "radius-avatar" to r.avatar,
                "radius-control" to r.control,
                "radius-floating" to r.floating,
                "radius-handle" to r.handle,
                "stroke-hairline" to k.hairline,
                "stroke-outline" to k.outline,
                "stroke-track" to k.track,
                "stroke-progress" to k.progress,
                "stroke-signal" to k.signal,
                "stroke-ring" to k.ring,
                "dash-on" to k.dashOn,
                "dash-off" to k.dashOff,
            )
    }

    private fun sizeTokens(): Map<String, Dp> {
        val z = HlSizes()
        return mapOf(
            "size-icon-medium" to z.iconMedium,
            "size-icon-micro" to z.iconMicro,
            "size-icon" to z.icon,
            "size-control-lg" to z.controlLarge,
            "size-tab" to z.tab,
            "size-tab-w" to z.tabWidth,
            "size-bar" to z.bar,
            "size-chip" to z.chip,
            "size-pill" to z.pill,
            "size-pill-compact" to z.pillCompact,
            "size-dot" to z.dot,
            "size-dot-small" to z.dotSmall,
            "size-dot-large" to z.dotLarge,
            "size-gauge" to z.gauge,
            "size-gauge-inset" to z.gaugeInset,
            "size-ring" to z.ring,
            "size-signal" to z.signal,
            "size-avatar" to z.avatar,
            "size-action" to z.action,
            "size-icon-small" to z.iconSmall,
            "size-icon-tiny" to z.iconTiny,
            "size-indicator" to z.indicator,
            "size-tick" to z.tick,
            "size-tile" to z.tile,
            "size-hero-card" to z.heroCard,
            "size-stat-card" to z.statCard,
            "size-tab-bar" to z.tabBar,
            "size-content-max" to z.contentMax,
            "size-handle-width" to z.handleWidth,
            "size-handle-height" to z.handleHeight,
        )
    }

    @Test
    fun `motion uses the motion tokens`() {
        val css = tokens("bento.css")

        fun number(name: String) = css.getValue(name).removeSuffix("ms").toFloat()
        val m = HlMotion()
        val expected =
            mapOf(
                "motion-damping" to m.damping,
                "motion-stiffness" to m.stiffness,
                "motion-stiffness-press" to m.pressStiffness,
                "motion-press-scale" to m.pressScale,
                "motion-tab-shift" to m.tabShift,
                "motion-fade" to m.fadeMillis.toFloat(),
                "motion-pulse" to m.pulseMillis.toFloat(),
                "motion-pulse-alpha" to m.pulseAlpha,
            )

        assertThat(expected.keys.sorted()).isEqualTo(css.keys.filter { it.startsWith("motion-") }.sorted())
        expected.forEach { (name, value) -> assertThat(value, name).isEqualTo(number(name)) }
    }
}

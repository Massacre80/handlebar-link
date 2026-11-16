package com.abid.handlebarlink.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
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
            "glowInner" -> glowInner
            "glowMid" -> glowMid
            "signal" -> signal
            "action" -> action
            "onAction" -> onAction
            "track" -> track
            "gauge" -> gauge
            "shadow" -> shadow
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
                "tab" to Triple(t.tab, "text-tab" to "leading-tab", "tracking-tight"),
                "caps" to Triple(t.caps, "text-tab" to "leading-tab", "tracking-caps"),
            )
        expected.forEach { (name, spec) ->
            val (style, sizes, tracking) = spec
            assertThat(style.fontSize.value, "$name size").isEqualTo(px(sizes.first))
            assertThat(style.lineHeight.value, "$name leading").isEqualTo(px(sizes.second))
            assertThat(style.letterSpacing.value, "$name tracking").isEqualTo(em(tracking))
        }
    }

    @Test
    fun `spacing, sizes and radii use the dimension tokens`() {
        val css = tokens("bento.css")

        fun dp(name: String) = css.getValue(name).removeSuffix("px").toFloat()
        val s = HlSpacing()
        val z = HlSizes()
        val r = HlRadii()
        val expected: Map<String, Dp> =
            mapOf(
                "spacing-4" to s.s4,
                "spacing-8" to s.s8,
                "spacing-12" to s.s12,
                "spacing-14" to s.s14,
                "spacing-16" to s.s16,
                "spacing-20" to s.s20,
                "spacing-24" to s.s24,
                "size-icon" to z.icon,
                "size-control-lg" to z.controlLarge,
                "size-tab" to z.tab,
                "size-tab-w" to z.tabWidth,
                "size-bar" to z.bar,
                "size-chip" to z.chip,
                "radius-card" to r.card,
                "radius-hero" to r.hero,
                "radius-full" to r.full,
                "radius-nested" to r.nested,
                "radius-avatar" to r.avatar,
                "radius-control" to r.control,
            )
        val tokens = css.keys.filter { it.startsWith("spacing-") || it.startsWith("size-") || it.startsWith("radius-") }

        assertThat(expected.keys.sorted()).isEqualTo(tokens.sorted())
        expected.forEach { (name, value) -> assertThat(value.value, name).isEqualTo(dp(name)) }
    }
}

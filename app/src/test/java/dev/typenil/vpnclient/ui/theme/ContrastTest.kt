package dev.typenil.vpnclient.ui.theme

import androidx.compose.ui.graphics.Color
import dev.typenil.vpnclient.ui.home.VisualState
import dev.typenil.vpnclient.ui.home.tone
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x contrast of two opaque colors, 1.0..21.0. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun luminance(c: Color): Double {
    fun lin(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

private fun over(fg: Color, alpha: Float, bg: Color) = Color(
    red = fg.red * alpha + bg.red * (1 - alpha),
    green = fg.green * alpha + bg.green * (1 - alpha),
    blue = fg.blue * alpha + bg.blue * (1 - alpha),
)

class ContrastTest {
    private val themes = listOf("light" to LightAfterglow, "dark" to DarkAfterglow)

    private class Check(val name: String, val min: Double, val fg: (AfterglowPalette) -> Color, val bg: (AfterglowPalette) -> Color)

    private val text = 4.5
    private val nonText = 3.0

    private val pairs = listOf(
        Check("ink/paper", text, { it.ink }, { it.paper }),
        Check("ink/paperSecondary", text, { it.ink }, { it.paperSecondary }),
        Check("ink/surfaceElevated", text, { it.ink }, { it.surfaceElevated }),
        Check("ink/errorSurface", text, { it.ink }, { it.errorSurface }),
        Check("muted/paper", text, { it.muted }, { it.paper }),
        Check("muted/paperSecondary", text, { it.muted }, { it.paperSecondary }),
        Check("muted/surfaceElevated", text, { it.muted }, { it.surfaceElevated }),
        Check("coral/paper", text, { it.coral }, { it.paper }),
        Check("coral/paperSecondary", text, { it.coral }, { it.paperSecondary }),
        Check("coral/surfaceElevated", text, { it.coral }, { it.surfaceElevated }),
        Check("coral/errorSurface", text, { it.coral }, { it.errorSurface }),
        Check("onInk/coral (primary button)", text, { it.onInk }, { it.coral }),
        Check("error/paper", text, { it.error }, { it.paper }),
        Check("error/paperSecondary", text, { it.error }, { it.paperSecondary }),
        Check("error/errorSurface", text, { it.error }, { it.errorSurface }),
        Check("error/surfaceElevated (last error value)", text, { it.error }, { it.surfaceElevated }),
        Check("onInk/error (destructive button)", text, { it.onInk }, { it.error }),
        Check("onActionSurface/actionSurface", text, { it.onActionSurface }, { it.actionSurface }),
        Check("onActionSurface 72%/actionSurface (CTA subtitle)", text,
            { over(it.onActionSurface, .72f, it.actionSurface) }, { it.actionSurface }),
        Check("borderStrong/paper (field border)", nonText, { it.borderStrong }, { it.paper }),
        Check("borderStrong/paperSecondary (field border)", nonText, { it.borderStrong }, { it.paperSecondary }),
        Check("borderStrong/surfaceElevated (field border)", nonText, { it.borderStrong }, { it.surfaceElevated }),
        // Statistics direction tiles: arrow glyph over the tinted tile (home card = surfaceElevated).
        Check("ink arrow/jade stat tile", text,
            { it.ink }, { over(it.jade, AfterglowTokens.statTileTint, it.surfaceElevated) }),
        Check("coral arrow/coral stat tile", text,
            { it.coral }, { over(it.coral, AfterglowTokens.statTileTint, it.surfaceElevated) }),
    )

    @Test
    fun `surface text and control pairs meet WCAG AA in both themes`() {
        val failures = themes.flatMap { (theme, p) ->
            pairs.mapNotNull { pair ->
                val r = contrastRatio(pair.fg(p), pair.bg(p))
                if (r < pair.min) "$theme ${pair.name}: %.2f < ${pair.min}".format(r) else null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `every state tone keeps hero text and action icon readable in both themes`() {
        val failures = mutableListOf<String>()
        for ((theme, p) in themes) for (tone in StateTone.entries) {
            val s = p.stateColors(tone)
            val chip = over(s.onHero, .14f, s.hero)
            fun check(name: String, fg: Color, bg: Color, min: Double) {
                val r = contrastRatio(fg, bg)
                if (r < min) failures += "$theme $tone $name: %.2f < $min".format(r)
            }
            check("onHero/hero", s.onHero, s.hero, text)
            check("onHeroMuted/hero", s.onHeroMuted, s.hero, text)
            check("onHero/latency chip", s.onHero, chip, text)
            check("actionSurface on accent (CTA icon)", p.actionSurface, s.accent, nonText)
            check("accent on actionSurface", s.accent, p.actionSurface, nonText)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `heroes differ between themes and between tones`() {
        for (tone in StateTone.entries) {
            assertTrue("$tone hero must differ light vs dark",
                LightAfterglow.stateColors(tone).hero != DarkAfterglow.stateColors(tone).hero)
        }
        for ((theme, p) in themes) {
            assertEquals("$theme heroes must be unique", 4, StateTone.entries.map { p.stateColors(it).hero }.toSet().size)
        }
    }

    @Test
    fun `only the error tone is red`() {
        fun redDominance(c: Color) = c.red - maxOf(c.green, c.blue)
        for ((theme, p) in themes) {
            assertTrue("$theme error hero is red", redDominance(p.heroError) > .15f)
            assertTrue("$theme neutral hero is not red", redDominance(p.heroNeutral) < .08f)
            assertTrue("$theme positive hero is not red", redDominance(p.heroPositive) < 0f)
            assertTrue("$theme progress hero is warm but not error red",
                redDominance(p.heroProgress) in .0f..redDominance(p.heroError) - .02f && p.heroProgress.green > p.heroError.green)
        }
    }

    @Test
    fun `visual states map to tones`() {
        assertEquals(StateTone.Neutral, VisualState.Offline.tone())
        assertEquals(StateTone.Neutral, VisualState.Stopping.tone())
        for (s in listOf(VisualState.Preparing, VisualState.Permission, VisualState.Connecting, VisualState.Reconnecting))
            assertEquals(StateTone.Progress, s.tone())
        assertEquals(StateTone.Positive, VisualState.Connected.tone())
        assertEquals(StateTone.Error, VisualState.Error.tone())
    }
}

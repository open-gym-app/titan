package com.opengym.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG AA, asserted rather than recorded.
 *
 * A contrast figure pasted into a pull request describes the palette on the day somebody
 * checked it. This fails the build the day somebody nudges a token — which is the only
 * moment the check is worth anything, and the moment a screenshot diff will not catch it,
 * because a screenshot compares the new rendering against a reference re-recorded in the
 * same commit.
 *
 * Two thresholds, per WCAG 2.1:
 *
 * - **4.5:1 for text.** Every `onX`/`X` pair, plus the combinations the screens actually
 *   produce that Material does not name — accent text on a card, supporting text on a
 *   raised fill.
 * - **3:1 for a control boundary** (1.4.11 Non-text Contrast). `outline` draws the edge of
 *   outlined buttons and unselected chips, and that edge is the only thing saying where the
 *   control is.
 *
 * Hairlines are deliberately absent. `outlineVariant` separates content that is already
 * distinguishable without it, which 1.4.11 exempts as decorative — holding a row divider to
 * 3:1 would draw the ruled layout as a grid of black lines.
 */
class ColorSchemeContrastTest {

    private data class ContrastPair(
        val foreground: String,
        val background: String,
        val minimum: Double,
    )

    private val textPairs = listOf(
        ContrastPair("onPrimary", "primary", TEXT),
        ContrastPair("onPrimaryContainer", "primaryContainer", TEXT),
        ContrastPair("onSecondary", "secondary", TEXT),
        ContrastPair("onSecondaryContainer", "secondaryContainer", TEXT),
        ContrastPair("onTertiary", "tertiary", TEXT),
        ContrastPair("onTertiaryContainer", "tertiaryContainer", TEXT),
        ContrastPair("onError", "error", TEXT),
        ContrastPair("onErrorContainer", "errorContainer", TEXT),
        ContrastPair("onBackground", "background", TEXT),
        ContrastPair("onSurface", "surface", TEXT),
        ContrastPair("onSurfaceVariant", "surface", TEXT),
        ContrastPair("onSurfaceVariant", "surfaceVariant", TEXT),
        ContrastPair("inverseOnSurface", "inverseSurface", TEXT),
        // Body and supporting text sit on every surface level, not only on `surface`.
        ContrastPair("onSurface", "surfaceContainerLow", TEXT),
        ContrastPair("onSurface", "surfaceContainer", TEXT),
        ContrastPair("onSurface", "surfaceContainerHigh", TEXT),
        ContrastPair("onSurface", "surfaceContainerHighest", TEXT),
        ContrastPair("onSurfaceVariant", "surfaceContainerLow", TEXT),
        ContrastPair("onSurfaceVariant", "surfaceContainer", TEXT),
        // The two raised fills carry supporting text as well, and one of them carries a single
        // character: `SetTypeMarker` draws the warm-up's `W` in this pair, where there is no
        // surrounding word to help a reader who is missing half the contrast.
        ContrastPair("onSurfaceVariant", "surfaceContainerHigh", TEXT),
        ContrastPair("onSurfaceVariant", "surfaceContainerHighest", TEXT),
        // The accent used as text — an eyebrow, a PR badge, the "+ NEW" action.
        ContrastPair("primary", "surface", TEXT),
        ContrastPair("primary", "background", TEXT),
        ContrastPair("primary", "surfaceContainerLow", TEXT),
        ContrastPair("error", "surface", TEXT),
    )

    private val boundaryPairs = listOf(
        ContrastPair("outline", "surface", CONTROL),
        ContrastPair("outline", "surfaceContainerLow", CONTROL),
        ContrastPair("outline", "surfaceContainer", CONTROL),
        // A filled accent control against the fill it sits on.
        ContrastPair("primary", "surfaceContainer", CONTROL),
    )

    @Test
    fun `light scheme text meets WCAG AA`() {
        assertMeetsMinimum("light", OpenGymLightColorScheme, textPairs)
    }

    @Test
    fun `dark scheme text meets WCAG AA`() {
        assertMeetsMinimum("dark", OpenGymDarkColorScheme, textPairs)
    }

    @Test
    fun `light scheme control boundaries meet WCAG AA`() {
        assertMeetsMinimum("light", OpenGymLightColorScheme, boundaryPairs)
    }

    @Test
    fun `dark scheme control boundaries meet WCAG AA`() {
        assertMeetsMinimum("dark", OpenGymDarkColorScheme, boundaryPairs)
    }

    @Test
    fun `a plate's stamped weight reads on the plate, in both schemes`() {
        // The ramp runs from the accent through to a pale grey, so no single text colour works
        // across it — each tone names its own, and this is what stops a mid-tone being added
        // later that swallows the number written on it.
        listOf("light" to OpenGymLightPlateColors, "dark" to OpenGymDarkPlateColors)
            .forEach { (name, plates) ->
                val failures = plates.tones.mapIndexedNotNull { rank, tone ->
                    val ratio = contrast(tone.label, tone.fill)
                    "plate tone %d = %.2f:1 (needs %.1f:1)".format(rank, ratio, TEXT)
                        .takeIf { ratio < TEXT }
                }
                assertTrue("$name ramp fails WCAG AA:\n" + failures.joinToString("\n"), failures.isEmpty())
            }
    }

    @Test
    fun `a plate is distinguishable from the plate above it in the ramp`() {
        // Rank is the only thing the ramp encodes, so two adjacent tones that look alike encode
        // nothing. 1.3:1 is well under a text threshold and is the right question here: these
        // are big filled shapes side by side, not glyphs.
        listOf(OpenGymLightPlateColors, OpenGymDarkPlateColors).forEach { plates ->
            plates.tones.zipWithNext().forEachIndexed { rank, (heavier, lighter) ->
                val ratio = contrast(heavier.fill, lighter.fill)
                assertTrue("tones $rank and ${rank + 1} are $ratio:1 apart", ratio >= ADJACENT_PLATES)
            }
        }
    }

    @Test
    fun `the workout's chrome reads on its own bar, in both schemes`() {
        // The header is the one surface that is not part of either colour scheme — it stays
        // dark in both — so `assertMeetsMinimum` cannot reach it. Everything written on it is
        // still text on a background, and holds to the same 4.5:1.
        //
        // `fill` is absent as a background-vs-bar pair on purpose: it is the ground of the
        // pause button, whose glyph is `onSurface` and carries the contrast on its own. A
        // filled control does not owe 3:1 against the surface behind it when what identifies
        // it is drawn at full strength inside it.
        listOf("light" to OpenGymLightSessionChrome, "dark" to OpenGymDarkSessionChrome)
            .forEach { (name, chrome) ->
                val pairs = listOf(
                    Triple("onSurface", chrome.onSurface, chrome.surface),
                    Triple("onSurfaceVariant", chrome.onSurfaceVariant, chrome.surface),
                    Triple("accent", chrome.accent, chrome.surface),
                    Triple("onAccent", chrome.onAccent, chrome.accent),
                    // The pause button's glyph, on the button rather than on the bar.
                    Triple("onSurface on fill", chrome.onSurface, chrome.fill),
                )
                val failures = pairs.mapNotNull { (label, foreground, background) ->
                    val ratio = contrast(foreground, background)
                    "%s = %.2f:1 (needs %.1f:1)".format(label, ratio, TEXT).takeIf { ratio < TEXT }
                }
                assertTrue(
                    "$name session chrome fails WCAG AA:\n" + failures.joinToString("\n"),
                    failures.isEmpty(),
                )
            }
    }

    @Test
    fun `the celebration's tones carry their own labels, in both schemes`() {
        // `OpenGymCelebrationColors` claims this check by name: the payoff's palette is not part
        // of either colour scheme, so `assertMeetsMinimum` cannot reach it, and every ground
        // there names the text colour that reads on it exactly as a plate tone does.
        //
        // The banner is a *gradient*, and both of its stops are measured rather than the tone
        // the centred text happens to sit over. A far stop that fails on its own is the failure
        // this misses if it is not looked for: the words are centred over the middle of the
        // ramp, so nothing about the screen looks wrong while the end of the band is under AA.
        listOf(
            Triple("light", OpenGymLightCelebrationColors, OpenGymLightColorScheme),
            Triple("dark", OpenGymDarkCelebrationColors, OpenGymDarkColorScheme),
        ).forEach { (name, celebration, scheme) ->
            val pairs = listOf(
                Triple("onXp on xp", contrast(celebration.onXp, celebration.xp), TEXT),
                Triple("onLevelUp on levelUp", contrast(celebration.onLevelUp, celebration.levelUp), TEXT),
                Triple("onLevelUp on levelUpEdge", contrast(celebration.onLevelUp, celebration.levelUpEdge), TEXT),
                Triple(
                    "onAchievement on achievement",
                    contrast(celebration.onAchievement, celebration.achievement),
                    TEXT,
                ),
                // The XP figure is the one celebration tone drawn as *text on a card* rather
                // than as a fill with its own label — the same treatment, and the same
                // threshold, as the accent's own `primary on surfaceContainerLow` above.
                Triple("xp on the card", contrast(celebration.xp, scheme.surfaceContainerLow), TEXT),
                // The level bar encodes how far into the level the user is entirely in the
                // difference between these two, which is the progress rail's argument for 3:1
                // rather than a text threshold.
                Triple("xp against xpTrack", contrast(celebration.xp, celebration.xpTrack), CONTROL),
            )
            val failures = pairs.mapNotNull { (label, ratio, minimum) ->
                "%s = %.2f:1 (needs %.1f:1)".format(label, ratio, minimum).takeIf { ratio < minimum }
            }
            assertTrue(
                "$name celebration fails WCAG AA:\n" + failures.joinToString("\n"),
                failures.isEmpty(),
            )
        }
    }

    @Test
    fun `a quest mark's glyph reads on its own ground, in both schemes`() {
        // `OpenGymQuestColors` claims this check by name. Three tinted grounds, each carrying an
        // icon and nothing else — so the threshold is the **control** one: an icon is a
        // non-text graphical object, and holding a 40dp glyph to a body-text ratio would push
        // every ground to a tint so pale it stopped distinguishing the three metrics at all.
        //
        // The ground itself is measured against the card it sits on too. A mark that meets AA
        // against its glyph and disappears into the surface behind it is a mark nobody can find,
        // which is the whole job it was added to do.
        listOf(
            Triple("light", OpenGymLightQuestColors, OpenGymLightColorScheme),
            Triple("dark", OpenGymDarkQuestColors, OpenGymDarkColorScheme),
        ).forEach { (name, quest, scheme) ->
            val pairs = listOf(
                Triple("onSessions on sessions", contrast(quest.onSessions, quest.sessions), CONTROL),
                Triple("onRecords on records", contrast(quest.onRecords, quest.records), CONTROL),
                Triple("onVolume on volume", contrast(quest.onVolume, quest.volume), CONTROL),
                Triple("sessions on the card", contrast(quest.sessions, scheme.surfaceContainerLow), MARK),
                Triple("records on the card", contrast(quest.records, scheme.surfaceContainerLow), MARK),
                Triple("volume on the card", contrast(quest.volume, scheme.surfaceContainerLow), MARK),
            )
            val failures = pairs.mapNotNull { (label, ratio, minimum) ->
                "%s = %.2f:1 (needs %.1f:1)".format(label, ratio, minimum).takeIf { ratio < minimum }
            }
            assertTrue(
                "$name quest marks fail:\n" + failures.joinToString("\n"),
                failures.isEmpty(),
            )
        }
    }

    @Test
    fun `a medal's glyph reads on its own metal, in both schemes`() {
        // `OpenGymMedalColors` claims this check by name. The threshold is the **control** one for
        // the same reason the quest marks take it: the glyph struck into a disc is a graphical
        // object, not a line of text, and holding it to a body-text ratio would force every metal
        // towards a tone that stopped looking like metal.
        //
        // Each metal is measured against the card behind it as well. A gold that meets AA against
        // its own glyph and dissolves into the surface is a medal nobody can see — and the locked
        // disc is the one most at risk of it, since it is a neutral on a neutral by design.
        listOf(
            Triple("light", OpenGymLightMedalColors, OpenGymLightColorScheme),
            Triple("dark", OpenGymDarkMedalColors, OpenGymDarkColorScheme),
        ).forEach { (name, medal, scheme) ->
            val pairs = listOf(
                Triple("goldGlyph on goldFace", contrast(medal.goldGlyph, medal.goldFace), CONTROL),
                Triple("silverGlyph on silverFace", contrast(medal.silverGlyph, medal.silverFace), CONTROL),
                Triple("bronzeGlyph on bronzeFace", contrast(medal.bronzeGlyph, medal.bronzeFace), CONTROL),
                Triple("lockedGlyph on lockedFace", contrast(medal.lockedGlyph, medal.lockedFace), CONTROL),
                // The rim is what gives a disc an edge; a rim that matched its own face would
                // leave the medal a flat circle with no boundary at all.
                Triple("goldRim against goldFace", contrast(medal.goldRim, medal.goldFace), MARK),
                Triple("silverRim against silverFace", contrast(medal.silverRim, medal.silverFace), MARK),
                Triple("bronzeRim against bronzeFace", contrast(medal.bronzeRim, medal.bronzeFace), MARK),
                Triple("lockedRim against lockedFace", contrast(medal.lockedRim, medal.lockedFace), MARK),
                Triple("gold on the card", contrast(medal.goldFace, scheme.surfaceContainerLow), MARK),
                Triple("silver on the card", contrast(medal.silverFace, scheme.surfaceContainerLow), MARK),
                Triple("bronze on the card", contrast(medal.bronzeFace, scheme.surfaceContainerLow), MARK),
                Triple("locked on the card", contrast(medal.lockedFace, scheme.surfaceContainerLow), MARK),
            )
            val failures = pairs.mapNotNull { (label, ratio, minimum) ->
                "%s = %.2f:1 (needs %.1f:1)".format(label, ratio, minimum).takeIf { ratio < minimum }
            }
            assertTrue(
                "$name medals fail:\n" + failures.joinToString("\n"),
                failures.isEmpty(),
            )
        }
    }

    @Test
    fun `the progress rail's two states are told apart`() {
        // The rail encodes one thing — how much of the workout is behind you — and it encodes
        // it entirely in the difference between these two colours. 3:1 rather than 1.3:1 as
        // the plate ramp uses: these are 3dp slivers rather than big filled shapes.
        listOf("light" to OpenGymLightSessionChrome, "dark" to OpenGymDarkSessionChrome)
            .forEach { (name, chrome) ->
                val ratio = contrast(chrome.accent, chrome.track)
                assertTrue("$name rail is only %.2f:1 apart".format(ratio), ratio >= CONTROL)
            }
    }

    @Test
    fun `negative - the checker rejects a pair that does not meet AA`() {
        // Mid-grey on white is 3.45:1. If this ever passes, the assertions above prove
        // nothing, so the guard is worth more than the case it covers.
        assertTrue(contrast(Color(0xFF8A8A8A), Color(0xFFFFFFFF)) < TEXT)
    }

    private fun assertMeetsMinimum(
        name: String,
        scheme: ColorScheme,
        pairs: List<ContrastPair>,
    ) {
        val failures = pairs.mapNotNull { pair ->
            val ratio = contrast(scheme.slot(pair.foreground), scheme.slot(pair.background))
            "%s on %s = %.2f:1 (needs %.1f:1)".format(pair.foreground, pair.background, ratio, pair.minimum)
                .takeIf { ratio < pair.minimum }
        }
        assertTrue("$name scheme fails WCAG AA:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Named rather than passed as a `Color` so a failure message says which *slot* is wrong.
     * A hex value in the output would send the reader hunting for which token it was.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun ColorScheme.slot(name: String): Color =
        when (name) {
            "primary" -> primary
            "onPrimary" -> onPrimary
            "primaryContainer" -> primaryContainer
            "onPrimaryContainer" -> onPrimaryContainer
            "secondary" -> secondary
            "onSecondary" -> onSecondary
            "secondaryContainer" -> secondaryContainer
            "onSecondaryContainer" -> onSecondaryContainer
            "tertiary" -> tertiary
            "onTertiary" -> onTertiary
            "tertiaryContainer" -> tertiaryContainer
            "onTertiaryContainer" -> onTertiaryContainer
            "error" -> error
            "onError" -> onError
            "errorContainer" -> errorContainer
            "onErrorContainer" -> onErrorContainer
            "background" -> background
            "onBackground" -> onBackground
            "surface" -> surface
            "onSurface" -> onSurface
            "surfaceVariant" -> surfaceVariant
            "onSurfaceVariant" -> onSurfaceVariant
            "surfaceContainerLow" -> surfaceContainerLow
            "surfaceContainer" -> surfaceContainer
            "surfaceContainerHigh" -> surfaceContainerHigh
            "surfaceContainerHighest" -> surfaceContainerHighest
            "inverseSurface" -> inverseSurface
            "inverseOnSurface" -> inverseOnSurface
            "outline" -> outline
            else -> throw IllegalArgumentException("Unknown colour slot '$name'")
        }

    private companion object {
        /** WCAG 2.1 §1.4.3, normal-size text. */
        const val TEXT = 4.5

        /** WCAG 2.1 §1.4.11, the boundary of a user-interface component. */
        const val CONTROL = 3.0

        /** Not a WCAG figure: two big filled shapes next to each other, told apart. */
        const val ADJACENT_PLATES = 1.3

        /**
         * Not a WCAG figure either: a tinted mark, told apart from the card behind it.
         *
         * Lower than [CONTROL] on purpose. A quest mark is not a control and carries no meaning
         * a reader can lose — the glyph inside it and the row's own words both say what the
         * quest counts — so what this holds is that the tile reads as a *shape* on the surface
         * rather than dissolving into it. Pushing it to 3:1 would force three grounds dark
         * enough to compete with the accent, which is the thing the palette exists not to do.
         */
        const val MARK = 1.15

        fun contrast(
            a: Color,
            b: Color,
        ): Double {
            val (lighter, darker) = listOf(luminance(a), luminance(b)).sortedDescending()
            return (lighter + 0.05) / (darker + 0.05)
        }

        /** WCAG 2.1 relative luminance. Spelled out rather than taken from `Color.luminance()`
         *  so the test measures the standard, not Compose's implementation of it. */
        fun luminance(color: Color): Double =
            0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
    }
}

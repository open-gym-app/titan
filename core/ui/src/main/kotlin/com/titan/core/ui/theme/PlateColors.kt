package com.titan.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colours a loaded barbell is drawn in, reached through [TitanTheme.plateColors].
 *
 * ### Why this is not the IWF palette
 *
 * Real competition plates are colour-coded — 25 kg red, 20 blue, 15 yellow, 10 green — and
 * copying that is the obvious move. It is the wrong one here, twice over.
 *
 * The first reason is the design system's own rule: this app has *one* accent, and everything
 * that matters is drawn in it. Four saturated hues on one screen would make the plate diagram
 * the loudest thing in the app and leave the accent meaning "decorated" rather than "this is
 * the thing". The wireframes draw the calculator with the accent and three neutrals for exactly
 * that reason.
 *
 * The second is that the coding is not universal. A pound rack runs blue-yellow-green-grey at
 * different weights, a commercial gym's bumpers are usually all black, and a home rack is cast
 * iron. A colour that claims to be the plate's own colour and is not is worse than one that
 * obviously is not trying to be.
 *
 * ### What the ramp means instead
 *
 * Rank, not weight. The heaviest size on the rack is the accent and each lighter one steps
 * down through the neutrals, so the diagram reads as *big to small* at a glance in either unit
 * system and on a rack of any shape. A user with only 20s and 10s gets the accent on the 20s;
 * a user with the full metric rack gets it on the 25s. There is no absolute mapping to remember
 * and nothing to get wrong when somebody adds a size.
 *
 * ### Every tone carries its own label colour
 *
 * A plate is drawn with its weight stamped on it, and the ramp runs from a dark accent through
 * to a pale grey, so no single text colour works across it. Each tone therefore names the one
 * that reads on it, and `ColorSchemeContrastTest` holds every pair to WCAG AA — which is what
 * stops the ramp being extended one day with a mid-tone that swallows its own label.
 */
@Immutable
data class TitanPlateColors(

    /**
     * The tones, heaviest size first. Seven of them, which covers both default racks — five
     * metric sizes and six imperial — with a tone in hand for a size a later rack adds. See
     * [toneFor] for what an eighth size gets.
     */
    val tones: List<PlateTone>,
) {
    /**
     * The tone for the size at [rank], counting from the heaviest at zero.
     *
     * A rack with more sizes than the ramp has tones repeats the last one rather than running
     * out. That is the honest degradation: past the seventh size the plates are so light that
     * they are drawn a few pixels tall anyway, and two of them sharing a shade is a smaller lie
     * than a plate drawn in no colour at all.
     */
    fun toneFor(rank: Int): PlateTone = tones[rank.coerceIn(0, tones.lastIndex)]
}

/**
 * One step of the ramp: what a plate is filled with, and what its stamped weight is written in.
 *
 * @property fill the plate's face.
 * @property label the weight on it — chosen per tone, not per scheme. See [TitanPlateColors].
 */
@Immutable
data class PlateTone(
    val fill: Color,
    val label: Color,
)

/*
 * The two ramps.
 *
 * Both run accent → dark → light, and both are drawn on the page background, so the pale end
 * of each would disappear without the hairline border `BarbellDiagram` puts round every plate.
 * The light ramp descends from ink to near-paper; the dark one *ascends* from near-white to the
 * raised fill, which is the same idea photographed the other way up — on a near-black ground it
 * is the pale plates that read as heavy, so the ramp has to invert with the scheme rather than
 * be reused across both.
 */

internal val TitanLightPlateColors = TitanPlateColors(
    tones = listOf(
        PlateTone(fill = Color(0xFFCC4226), label = Color(0xFFFFFFFF)),
        PlateTone(fill = Color(0xFF1A1A1A), label = Color(0xFFFAFAF7)),
        PlateTone(fill = Color(0xFF4F4F4C), label = Color(0xFFFAFAF7)),
        PlateTone(fill = Color(0xFF6E6E6A), label = Color(0xFFFAFAF7)),
        PlateTone(fill = Color(0xFF9A9A94), label = Color(0xFF1A1A1A)),
        PlateTone(fill = Color(0xFFC0C0BA), label = Color(0xFF1A1A1A)),
        PlateTone(fill = Color(0xFFE0E0DA), label = Color(0xFF1A1A1A)),
    ),
)

internal val TitanDarkPlateColors = TitanPlateColors(
    tones = listOf(
        PlateTone(fill = Color(0xFFFF6B3D), label = Color(0xFF2B0D02)),
        PlateTone(fill = Color(0xFFECECEA), label = Color(0xFF1A1A1A)),
        PlateTone(fill = Color(0xFFB4B4B0), label = Color(0xFF1A1A1A)),
        PlateTone(fill = Color(0xFF8A8A84), label = Color(0xFF1A1A1A)),
        PlateTone(fill = Color(0xFF5F5F66), label = Color(0xFFECECEA)),
        PlateTone(fill = Color(0xFF43434A), label = Color(0xFFECECEA)),
        PlateTone(fill = Color(0xFF2C2C35), label = Color(0xFFECECEA)),
    ),
)

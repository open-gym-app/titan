package com.titan.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The metals an achievement medal is struck in, reached through [TitanTheme.medal].
 *
 * ### Why a medal is allowed three more hues
 *
 * [TitanPlateColors] argues that four saturated hues leave the accent meaning "decorated";
 * [TitanCelebrationColors] carves out the exception for a surface that *is* the content, and
 * [TitanQuestColors] tightens it to hues the app already owns. The achievements screen is the
 * third such surface and the metals are its content — but the rule they answer to is different
 * again, and worth stating, because "bronze, silver, gold" is not a palette decision at all.
 *
 * It is the one ranking a reader knows without a legend. A gold medal outranks a bronze one to
 * everybody who has ever seen a podium, in every locale this app will ship in, with no key on the
 * screen and no sentence explaining it. Encoding that rank in the app's own accent at three
 * strengths would have been the disciplined choice and it would have thrown that away: three
 * oranges are three oranges, and the user would have had to be *told* which was worth more.
 *
 * So the hues here are not decoration and they are not new signals loose in the app. They are
 * bounded the way the celebration's are: reachable only from the achievements screen and the
 * milestone rows of the just-finished recap, never beside a set row or a rest timer, and never
 * carrying the rank *alone* — the tier is in [com.titan.core.domain.gamification.tier], the
 * name of the milestone is under every medal, and a reader who sees no colour at all loses only
 * the podium.
 *
 * ### Each metal is three tones, because a flat disc is a coin
 *
 * A medal reads as struck metal rather than as a circle of paint because its facets catch light
 * unevenly. [face] and [edge] are the two sides of that — alternating wedges around the disc, one
 * lit and one in shade — and [rim] is the darker ring that bounds it. Deriving [edge] and [rim]
 * from [face] by a fixed multiplier was the first shape of this and it looked like grey plastic
 * in the dark scheme, where a mechanical darkening runs into the background rather than away from
 * it. Three named tones per metal, each chosen against its own scheme, is the same trade
 * [TitanCelebrationColors.levelUpEdge] makes.
 *
 * @property goldFace the lit facets of the top rung of a ladder.
 * @property goldEdge its shaded facets.
 * @property goldRim the ring bounding the disc, and the deepest of the three.
 * @property goldGlyph the mark struck into the middle. Reads on [goldFace] at AA.
 * @property silverFace the lit facets of a middle rung.
 * @property silverEdge its shaded facets.
 * @property silverRim its ring.
 * @property silverGlyph the mark struck into it.
 * @property bronzeFace the lit facets of a first rung — where every ladder starts.
 * @property bronzeEdge its shaded facets.
 * @property bronzeRim its ring.
 * @property bronzeGlyph the mark struck into it.
 * @property lockedFace the ground of a milestone not yet held. A neutral, deliberately: an
 *   unearned medal must not look like a fourth metal, and the moment it is earned the colour
 *   arriving is the whole event.
 * @property lockedEdge its shaded facets — a narrower gap than the metals', so a locked disc
 *   reads as flat rather than as a struck medal that happens to be grey.
 * @property lockedRim its ring.
 * @property lockedGlyph the padlock. Reads on [lockedFace] at the boundary contrast a shape is
 *   held to, not the text one: it is a picture, and the milestone's name is under it either way.
 * @property ribbon the band a held medal hangs from. The celebration's violet rather than the
 *   accent — the accent on this screen belongs to the count and to whatever is pressable, and a
 *   ribbon painted in it would put the loudest colour in the app behind eighteen discs.
 * @property ribbonShade the far side of the fold, which is what gives the band a direction.
 * @property shine the sweep that crosses a medal as it is struck. White at low alpha over the
 *   metals, and the one tone here that is not a colour so much as a light.
 */
@Immutable
data class TitanMedalColors(
    val goldFace: Color,
    val goldEdge: Color,
    val goldRim: Color,
    val goldGlyph: Color,
    val silverFace: Color,
    val silverEdge: Color,
    val silverRim: Color,
    val silverGlyph: Color,
    val bronzeFace: Color,
    val bronzeEdge: Color,
    val bronzeRim: Color,
    val bronzeGlyph: Color,
    val lockedFace: Color,
    val lockedEdge: Color,
    val lockedRim: Color,
    val lockedGlyph: Color,
    val ribbon: Color,
    val ribbonShade: Color,
    val shine: Color,
)

/*
 * The light set.
 *
 * The three metals are pitched at roughly one luminance so that a wall of medals reads as a set
 * rather than as a gradient with the gold ones jumping forward — the rank is carried by hue, which
 * is the part a reader already knows how to order.
 *
 * Bronze is pulled towards brown rather than left at its natural orange: the app's accent is a
 * burnt orange, and a bronze any warmer would read as an accent-coloured disc — the one confusion
 * this set cannot afford, since the accent means something else on every other screen.
 */
internal val TitanLightMedalColors = TitanMedalColors(
    goldFace = Color(0xFFE8B33D),
    goldEdge = Color(0xFFC9901D),
    goldRim = Color(0xFF8A6209),
    goldGlyph = Color(0xFF3D2A02),
    silverFace = Color(0xFFC9CDD2),
    silverEdge = Color(0xFFA5ABB2),
    silverRim = Color(0xFF767C83),
    silverGlyph = Color(0xFF24282C),
    bronzeFace = Color(0xFFD08A55),
    bronzeEdge = Color(0xFFAE6A38),
    bronzeRim = Color(0xFF7E4A22),
    bronzeGlyph = Color(0xFF33190A),
    lockedFace = Color(0xFFDCDCD6),
    lockedEdge = Color(0xFFD0D0CA),
    lockedRim = Color(0xFFA9A9A3),
    lockedGlyph = Color(0xFF6B6B66),
    ribbon = Color(0xFF4B2A7B),
    ribbonShade = Color(0xFF34195C),
    shine = Color(0xCCFFFFFF),
)

/*
 * The dark set.
 *
 * The metals barely move. A medal is a lit object and the light does not change when the room
 * does — dropping the faces to a tint of themselves, the treatment the quest grounds take, would
 * leave three brown discs that no longer read as metal at all. What changes instead is the rim,
 * which deepens so the disc still has an edge against a near-black card.
 *
 * The locked ground is the tone that has to invert. A pale grey disc on `PaperDark` is a lamp, and
 * eighteen of them would be the brightest thing on a screen whose subject is the two that are
 * earned. It drops to a lifted charcoal, and the padlock lifts to meet it.
 */
internal val TitanDarkMedalColors = TitanMedalColors(
    goldFace = Color(0xFFD9A431),
    goldEdge = Color(0xFFB07F14),
    goldRim = Color(0xFF6E4F06),
    goldGlyph = Color(0xFF2A1C01),
    silverFace = Color(0xFFB6BCC2),
    silverEdge = Color(0xFF8E949B),
    silverRim = Color(0xFF5E646B),
    silverGlyph = Color(0xFF1B1E21),
    bronzeFace = Color(0xFFC17E48),
    bronzeEdge = Color(0xFF9A5F2F),
    bronzeRim = Color(0xFF66391A),
    bronzeGlyph = Color(0xFF2B1408),
    lockedFace = Color(0xFF2E2E36),
    lockedEdge = Color(0xFF26262D),
    lockedRim = Color(0xFF3E3E48),
    lockedGlyph = Color(0xFF9A9AA4),
    ribbon = Color(0xFF6B44A8),
    ribbonShade = Color(0xFF4A2C7A),
    shine = Color(0xB3FFFFFF),
)

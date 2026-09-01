package com.opengym.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colours the post-workout payoff is drawn in, reached through [OpenGymTheme.celebration].
 *
 * ### Why this is allowed to be loud when nothing else is
 *
 * The design system has one accent and [OpenGymPlateColors] argues at length for keeping it
 * that way: four saturated hues on a screen leave the accent meaning "decorated" rather than
 * "this is the thing". That argument is about the surfaces a lifter reads *while training* —
 * a set row, a rest timer, a PR badge — where colour is a signal to be found at a glance
 * between sets, and every extra hue is noise competing with it.
 *
 * The celebration is the other case. It is not a signal to be scanned; it **is** the content,
 * it appears on one screen in one mode, and it is gone the moment the user taps Done. Nothing
 * on that screen needs finding, because the screen is the finding. So the rule this set holds
 * to is narrower than the app's and stricter within its bounds:
 *
 * - it is reachable **only** from the just-finished recap — a historical recap of the same
 *   session draws none of it, see `SessionSummaryMode`;
 * - the accent stays the accent: [xp] *is* `primary`, so the number the screen is about is the
 *   same colour as every other figure that matters;
 * - the extra hues appear only in [levelUp] and [confetti], the two places celebrating a
 *   *crossing* rather than reporting a quantity.
 *
 * A second hue that survives past this screen is a bug in the design system, not a feature.
 *
 * ### Every tone carries its own label colour
 *
 * As with the plate ramp: a gradient that runs from a deep violet to the accent has no single
 * text colour that reads across it, so each tone names the one that does and
 * `ColorSchemeContrastTest` holds the pairs to WCAG AA. That is what stops the gradient being
 * restyled one day into something its own numbers disappear into.
 *
 * @property xp the accent the XP figure and its progress bar are drawn in. The app's own
 *   `primary`, deliberately duplicated here rather than read from the scheme at the call site,
 *   so this file is the one place the payoff's palette is described.
 * @property onXp what reads on [xp] when it is used as a fill.
 * @property xpTrack the unfilled remainder of the level bar — the distance still to go.
 * @property levelUp the crossing's own tone, used as the level-up banner's ground. The one
 *   hue in the app that is not the accent or a neutral, and it earns that by marking the one
 *   event the accent cannot: a level boundary is not "important", it is *different*.
 * @property onLevelUp what reads on [levelUp].
 * @property levelUpEdge the banner's far gradient stop, which the ground melts into. Kept
 *   beside its ground rather than derived, because a generated second stop is what turns a
 *   deliberate two-colour ramp into a muddy one on the next palette change.
 * @property achievement the ground an unlocked-milestone chip sits on.
 * @property onAchievement what reads on [achievement].
 * @property confetti the flourish's palette, in the order pieces are cycled through it.
 *   Multi-hue on purpose — confetti drawn in one colour is not confetti, it is a texture — and
 *   the one place in the app where a hue carries no meaning at all. Nothing is encoded here:
 *   a reader who cannot tell the pieces apart has lost nothing, which is what makes it safe.
 */
@Immutable
data class OpenGymCelebrationColors(
    val xp: Color,
    val onXp: Color,
    val xpTrack: Color,
    val levelUp: Color,
    val onLevelUp: Color,
    val levelUpEdge: Color,
    val achievement: Color,
    val onAchievement: Color,
    val confetti: List<Color>,
) {

    /**
     * The confetti colour for the piece at [index], cycling.
     *
     * Cycled rather than bounds-checked because the piece count is a motion parameter that is
     * tuned by eye — see `ConfettiBurst` — and a palette that ran out would make raising it
     * silently drop pieces to a single colour.
     */
    fun confettiFor(index: Int): Color = confetti[index.mod(confetti.size)]
}

/*
 * The light set.
 *
 * `levelUp` is a deep violet rather than a second warm hue: the accent is a burnt orange, and
 * a celebration painted in a neighbouring red-orange reads as more accent rather than as
 * something new. Violet is far enough round the wheel to be unmistakably a different event,
 * and dark enough in both schemes to carry white numerals at AA.
 */
internal val OpenGymLightCelebrationColors = OpenGymCelebrationColors(
    xp = Color(0xFFCC4226),
    onXp = Color(0xFFFFFFFF),
    xpTrack = Color(0xFFEDEDEA),
    levelUp = Color(0xFF4B2A7B),
    onLevelUp = Color(0xFFFFFFFF),
    levelUpEdge = Color(0xFF9A3B6B),
    achievement = Color(0xFFF6DFD6),
    onAchievement = Color(0xFF4A1408),
    confetti = listOf(
        Color(0xFFCC4226),
        Color(0xFF4B2A7B),
        Color(0xFF1F7A63),
        Color(0xFFD99A16),
        Color(0xFF9A3B6B),
    ),
)

/*
 * The dark set. Not a mechanical inversion — the grounds lift rather than darken, because a
 * violet dark enough to read on paper disappears against `PaperDark`, and the confetti hues
 * are raised to stay visible on a near-black field.
 */
internal val OpenGymDarkCelebrationColors = OpenGymCelebrationColors(
    xp = Color(0xFFFF6B3D),
    onXp = Color(0xFF2B0D02),
    xpTrack = Color(0xFF22222A),
    levelUp = Color(0xFF6B44A8),
    onLevelUp = Color(0xFFFFFFFF),
    levelUpEdge = Color(0xFFC2568C),
    achievement = Color(0xFF4A1E0E),
    onAchievement = Color(0xFFFFD9C7),
    confetti = listOf(
        Color(0xFFFF6B3D),
        Color(0xFF9A7BE0),
        Color(0xFF3FBF9F),
        Color(0xFFF2C14E),
        Color(0xFFE87BAE),
    ),
)

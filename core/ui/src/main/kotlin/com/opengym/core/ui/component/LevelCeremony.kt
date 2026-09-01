package com.opengym.core.ui.component

/**
 * How much ceremony a level is worth — the single place that question is answered.
 *
 * ### Why a rank and not a boolean
 *
 * Because a second kind of special level is already coming. Tiers, belts, ranks — whatever the
 * progression system grows — will want a crossing that is louder again than a round number, and
 * the shape that survives that is a scale rather than an `isMilestone` flag. Adding a case here
 * and a row to the table in `XpCelebrationCard` is the whole change; nothing in the banner, the
 * sweep or the confetti has to learn what a tier is.
 *
 * That is also why the flourish is expressed as *magnitudes* on this enum rather than as
 * branches at the call site. A caller asks how big, not which kind, so a new case is loud by
 * construction instead of silently falling through to the ordinary treatment — the failure mode a
 * `when` over kinds has, and the reason this is not one.
 *
 * ### Which levels are round
 *
 * Every fifth: a level ending in 0 or 5. Levels arrive quickly — a hundred points each, and a
 * first workout can pay four of them — so a milestone every tenth would be rare enough that most
 * users would meet the second one weeks apart and never learn the app had a louder register at
 * all. Five is frequent enough to be recognised as a pattern and rare enough to still mean
 * something. It is one constant, and moving it moves every consequence.
 *
 * @property kick how far the level number snaps before springing back, as a scale.
 * @property confettiPieces how heavy the burst is at this crossing.
 */
enum class LevelCeremony(
    val kick: Float,
    val confettiPieces: Int,
) {

    /** Any level. A kick and a burst, over in under a second. */
    ORDINARY(kick = 1.25f, confettiPieces = 40),

    /**
     * A round number — see the note above. The number kicks harder, the burst is half again as
     * heavy, and the banner names it, so the crossing reads as *different* rather than merely
     * bigger.
     */
    ROUND(kick = 1.45f, confettiPieces = 64),
    ;

    companion object {

        /**
         * What [level] is worth.
         *
         * The floor is [ORDINARY] rather than null: every crossing is celebrated, and the
         * question this answers is only how much.
         */
        fun of(level: Int): LevelCeremony = if (level > 0 && level % ROUND_EVERY == 0) ROUND else ORDINARY

        /** How often a level is a round one. See the note above before changing it. */
        const val ROUND_EVERY = 5
    }
}

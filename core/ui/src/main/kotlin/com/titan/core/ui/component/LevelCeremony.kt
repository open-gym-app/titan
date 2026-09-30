package com.titan.core.ui.component

/**
 * How much ceremony a level is worth — the single place that question is answered.
 *
 * ### Why a rank and not a boolean
 *
 * Because a second kind of special level is already coming. Tiers, belts, ranks — whatever the
 * progression system grows — will want a crossing that is louder again than a milestone, and
 * the shape that survives that is a scale rather than an `isMilestone` flag. Adding a case here
 * and a row to the table in `XpCelebrationCard` is the whole change; nothing in the banner, the
 * sweep or the confetti has to learn what a tier is.
 *
 * That is also why the flourish is expressed as *magnitudes* on this enum rather than as
 * branches at the call site. A caller asks how big, not which kind, so a new case is loud by
 * construction instead of silently falling through to the ordinary treatment — the failure mode a
 * `when` over kinds has, and the reason this is not one.
 *
 * ### Which levels are milestones
 *
 * Every fifth: a level ending in 0 or 5. Levels arrive quickly — a hundred points each, and a
 * first workout can pay four of them — so a milestone every tenth would be rare enough that most
 * users would meet the second one weeks apart and never learn the app had a louder register at
 * all. Five is frequent enough to be recognised as a pattern and rare enough to still mean
 * something. It is one constant, and moving it moves every consequence.
 *
 * ### A different animation, not the same one louder
 *
 * The magnitudes below are the smaller half of the difference. The one that does the work is
 * [confettiStyle]: an ordinary crossing **rains**, a milestone **detonates** outward from behind
 * the number. Two crossings that differed only in piece count would be one animation at two
 * volumes, and a reader would have to count confetti to tell them apart. A different shape of
 * movement is recognised before a word of the banner has been read.
 *
 * @property kick how far the level number snaps before springing back, as a scale.
 * @property confettiPieces how heavy the burst is at this crossing.
 * @property confettiStyle how the pieces move. See [ConfettiStyle].
 */
enum class LevelCeremony(
    val kick: Float,
    val confettiPieces: Int,
    val confettiStyle: ConfettiStyle,
) {

    /** Any level: a kick, and confetti falling over the banner. */
    ORDINARY(kick = 1.25f, confettiPieces = 40, confettiStyle = ConfettiStyle.RAIN),

    /**
     * Every fifth level — see the note above. The number kicks harder, the burst is half again as
     * heavy, it **explodes outward** rather than falling, and the banner names it. Four things,
     * of which only the third changes what the crossing *is*.
     */
    MILESTONE(kick = 1.45f, confettiPieces = 64, confettiStyle = ConfettiStyle.BURST),
    ;

    companion object {

        /**
         * What [level] is worth.
         *
         * The floor is [ORDINARY] rather than null: every crossing is celebrated, and the
         * question this answers is only how much.
         */
        fun of(level: Int): LevelCeremony = if (level > 0 && level % MILESTONE_EVERY == 0) MILESTONE else ORDINARY

        /** How often a level is a milestone. See the note above before changing it. */
        const val MILESTONE_EVERY = 5
    }
}

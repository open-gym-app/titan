package com.titan.core.domain.model

/**
 * What one [XpEvent] was worth, and where it left the user.
 *
 * Returned by [com.titan.core.domain.usecase.AwardXpUseCase] and produced inside the same
 * write that persisted the points, so [totalXp] and [level] are the figures actually stored
 * rather than a caller's re-read of them — a second read could observe another award landing
 * in between and report a level-up against the wrong event.
 *
 * @property awarded the points this event was worth, per
 *   [com.titan.core.domain.gamification.XpCalculator.xpFor].
 * @property totalXp the user's lifetime total **after** [awarded] was added.
 * @property previousLevel the level held before this event.
 * @property level the level held after it — the same as [previousLevel] unless this event
 *   crossed a threshold.
 */
data class XpAward(
    val awarded: Int,
    val totalXp: Long,
    val previousLevel: Int,
    val level: Int,
) {

    /**
     * Whether this event is the one that crossed a level threshold — what #27's celebration
     * fires on.
     *
     * True for exactly one event per *crossing*: the award that took the total past a
     * threshold, never the ones on either side of it. That is one event per level gained
     * only while no single award is worth more than a level — true of today's constants,
     * where the largest award (a monthly quest) is worth half a level. Retune one above
     * `XP_PER_LEVEL` and an award can clear two thresholds at once; it still reports a single
     * level-up, with [level] two above [previousLevel] rather than one, so a celebration
     * should announce the jump from [previousLevel] to [level] rather than assume it is one.
     */
    val leveledUp: Boolean get() = level > previousLevel
}

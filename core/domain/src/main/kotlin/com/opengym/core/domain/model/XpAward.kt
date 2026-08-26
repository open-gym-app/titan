package com.opengym.core.domain.model

/**
 * What one [XpEvent] was worth, and where it left the user.
 *
 * Returned by [com.opengym.core.domain.usecase.AwardXpUseCase] and produced inside the same
 * write that persisted the points, so [totalXp] and [level] are the figures actually stored
 * rather than a caller's re-read of them — a second read could observe another award landing
 * in between and report a level-up against the wrong event.
 *
 * @property awarded the points this event was worth, per
 *   [com.opengym.core.domain.gamification.XpCalculator.xpFor].
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
     * True for exactly one event per level gained: the award that took the total past the
     * threshold, never the ones on either side of it. A single award large enough to cross
     * two thresholds still reports one level-up, with [level] two above [previousLevel].
     */
    val leveledUp: Boolean get() = level > previousLevel
}

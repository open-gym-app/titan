package com.opengym.core.domain.model

import java.time.LocalDate

/**
 * One quest as a board draws it: the goal, how far along it is, and whether it is banked.
 *
 * ### [progress] and [isComplete] are not the same question
 *
 * A quest is complete when its completion has been **written** for the period, not when the
 * figures happen to reach the target. The two agree almost always and come apart in the case
 * that matters: records and sessions can be *deleted*. A user who cleared "three sessions this
 * week" and then removed one of those sessions has a progress figure that falls back to 2/3 and
 * a completion that stays — because the XP was paid, and this app does not take XP back.
 *
 * So a row can read `2/3` and still be complete, and the board must draw the completion rather
 * than re-deriving it from the bar. Deriving it would un-complete quests retroactively and let
 * the next session pay for the same quest twice.
 *
 * @property quest the definition, carrying the target, the period and the reward.
 * @property progress how much of [Quest.metric] has been done in [periodStart]'s period, in the
 *   **same unit as [Quest.target]** — a count for two of the metrics and milligrams for
 *   [QuestMetric.VOLUME_MILLIGRAMS], so a screen drawing the pair converts both or neither.
 *   Clamped to nothing: it can exceed the target, and a board that wants `5/3` to read as `3/3`
 *   clamps at the point it draws rather than losing the real figure here.
 * @property isComplete whether this quest's completion is on file for this period. See above:
 *   not `progress >= target`.
 * @property periodStart the first day of the period being reported, which is what a completion
 *   is keyed by and what a board reads to say when the quest resets.
 */
data class QuestProgress(
    val quest: Quest,
    val progress: Long,
    val isComplete: Boolean,
    val periodStart: LocalDate,
) {

    /** The last day of this period, inclusive — what a board counts down to. */
    val periodEnd: LocalDate get() = quest.period.endOf(periodStart)

    /**
     * How far along, from `0f` to `1f`, for a progress bar.
     *
     * Clamped at both ends: overshooting a target fills the bar rather than overflowing it, and
     * a completed quest whose figures later fell reads as full rather than dropping back — the
     * bar agrees with [isComplete], which is the one the user was paid on.
     */
    val fraction: Float
        get() = when {
            isComplete -> 1f
            else -> (progress.toFloat() / quest.target).coerceIn(0f, 1f)
        }
}

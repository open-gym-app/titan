package com.titan.core.domain.stats

import com.titan.core.domain.model.WorkoutSet

/**
 * The time and distance logged over a set of sets, in seconds and centimetres.
 *
 * What a run, a ride or a hold is quoted by in place of the volume it does not have, and the
 * timed twin of [VolumeCalculator]: one function per total, so the session's live stats, its
 * recap and anything after them answer the same rule rather than each writing out its own sum.
 * It must agree with the `SUM(COALESCE(ws.duration, 0))` and `SUM(COALESCE(ws.distance, 0))`
 * aggregates in `WorkoutSetDao` the same way [VolumeCalculator] agrees with the volume ones.
 */
object TimedWorkCalculator {

    /**
     * Total time of the completed sets in [sets], in seconds; an empty input is 0.
     *
     * A set with no duration counts as zero rather than being dropped — a set of curls is a real
     * set that was not timed — and placeholder sets are skipped here rather than by the caller,
     * for [VolumeCalculator.totalVolume]'s reason.
     */
    fun totalDuration(sets: Iterable<WorkoutSet>): Long = sets.sumOf { if (it.isCompleted) it.duration ?: 0L else 0L }

    /** Total distance of the completed sets in [sets], in centimetres, by [totalDuration]'s rules. */
    fun totalDistance(sets: Iterable<WorkoutSet>): Long = sets.sumOf { if (it.isCompleted) it.distance ?: 0L else 0L }
}

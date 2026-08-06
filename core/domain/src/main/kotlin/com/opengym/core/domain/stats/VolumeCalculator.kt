package com.opengym.core.domain.stats

import com.opengym.core.domain.model.WorkoutSet

/**
 * Training volume over logged sets, in milligram-reps.
 *
 * The unit follows from the schema: weights are stored in milligrams and nothing
 * physical is held as floating point, so `weight x reps` stays exact no matter how
 * many sets are summed — a 100-set session accumulates no rounding error at all.
 * Conversion to kg (or lb) happens in the UI layer, once, at the end.
 *
 * This is the domain-side twin of `WorkoutSetDao.getVolumeByWeek`, and it must
 * agree with it set for set: same `weight x reps`, same treatment of nulls, same
 * completed-only filter. Reads that span more history than one session should use
 * the DAO aggregate, which sums in SQLite; this is for a session already in memory.
 */
object VolumeCalculator {

    /**
     * `weight x reps` for one set, regardless of whether it is completed.
     *
     * A missing weight or missing reps counts as zero rather than being dropped —
     * cardio and timed holds are real sets with no volume — mirroring the
     * `COALESCE(...)` in the DAO aggregate.
     */
    fun setVolume(set: WorkoutSet): Long = (set.weight ?: 0L) * (set.reps ?: 0).toLong()

    /**
     * Total volume of the completed sets in [sets]; an empty input is 0.
     *
     * Placeholder sets (`isCompleted = false`) are skipped here rather than by the
     * caller, so no call site can inflate a total by forgetting to filter.
     */
    fun totalVolume(sets: Iterable<WorkoutSet>): Long =
        sets.sumOf { if (it.isCompleted) setVolume(it) else 0L }
}

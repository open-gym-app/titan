package com.titan.core.domain.stats

import com.titan.core.domain.model.WorkoutSet

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
 *
 * What the two do not share is *which* sets they are given. The aggregate reads only
 * closed-out sessions, since a week's volume has to mean the same thing as that week's
 * training days; this sums whatever it is handed, including a session still being logged,
 * which is what the recap of a workout in progress needs.
 */
object VolumeCalculator {

    /**
     * `weight x reps` for one set, regardless of whether it is completed.
     *
     * A missing weight or missing reps counts as zero rather than being dropped —
     * cardio and timed holds are real sets with no volume — mirroring the
     * `COALESCE(...)` in the DAO aggregate.
     *
     * Held at the `Long` bounds rather than wrapping, like the DAO aggregate's `TOTAL`. Every
     * set `SetValidator` admits is exact many times over; only a weight from a hand-edited
     * backup reaches the bound, and a wrapped product would read as negative work.
     */
    fun setVolume(set: WorkoutSet): Long = saturatingProduct(set.weight ?: 0L, (set.reps ?: 0).toLong())

    /**
     * Total volume of the completed sets in [sets]; an empty input is 0.
     *
     * Placeholder sets (`isCompleted = false`) are skipped here rather than by the
     * caller, so no call site can inflate a total by forgetting to filter. The sum saturates
     * as [setVolume] does.
     */
    fun totalVolume(sets: Iterable<WorkoutSet>): Long =
        sets.fold(0L) { total, set -> if (set.isCompleted) saturatingSum(total, setVolume(set)) else total }

    /**
     * [a] + [b], held at `Long.MAX_VALUE` or `Long.MIN_VALUE` where the sum would leave a `Long`.
     * Shared with `MuscleVolumeCalculator`, whose per-group totals are the same kind of sum.
     */
    internal fun saturatingSum(
        a: Long,
        b: Long,
    ): Long =
        try {
            Math.addExact(a, b)
        } catch (_: ArithmeticException) {
            if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
        }

    private fun saturatingProduct(
        a: Long,
        b: Long,
    ): Long =
        try {
            Math.multiplyExact(a, b)
        } catch (_: ArithmeticException) {
            if ((a < 0) == (b < 0)) Long.MAX_VALUE else Long.MIN_VALUE
        }
}
